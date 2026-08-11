/*
 *  Copyright (c) 2026, WSO2 LLC. (http://www.wso2.com)
 *
 *  WSO2 LLC. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package io.ballerina.flowmodelgenerator.extension;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.ballerina.flowmodelgenerator.core.copilot.CopilotLibraryManager;
import io.ballerina.flowmodelgenerator.core.copilot.model.Library;
import io.ballerina.flowmodelgenerator.core.copilot.model.ModelToJsonConverter;
import io.ballerina.modelgenerator.commons.PackageUtil;
import io.ballerina.projects.Package;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Dev harness that dumps the Copilot library wire payload for a fixed corpus of libraries, so the
 * rendered prompt text can be compared across a change to the trigger-metadata pipeline.
 *
 * <p>It calls exactly what the language server calls — {@link CopilotLibraryManager#loadFilteredLibraries}
 * followed by {@link ModelToJsonConverter#librariesToJson} — so the JSON written here is byte-for-byte the
 * payload the VS Code extension receives from {@code copilotLibraryManager/getFilteredLibraries}. Anything
 * less faithful would compare a render of something the Copilot never actually sees.
 *
 * <p><b>Version drift is the one confound this cannot remove.</b> {@code loadFilteredLibraries} resolves
 * whatever Central serves latest and exposes no version parameter, so a release landing between the before
 * and after runs would show up as a rendering difference that no code change caused. The resolved version of
 * every library is therefore recorded in {@code _versions.json}; passing the before run's copy back as the
 * third argument makes the after run compare against it and report every mismatch by name, so drift is
 * always visible rather than silently attributed to the implementation.
 *
 * <p>Not a test: it is excluded from {@code testng.xml} and is run through the {@code dumpCopilotRender}
 * Gradle task.
 *
 * @since 1.8.0
 */
public final class CopilotRenderDump {

    /**
     * The corpus, in two halves.
     *
     * <p>The first thirteen are every library the LS ships a trigger-metadata document for, which is the
     * surface the spec migration changes. The rest are controls served by paths the migration must not
     * touch — the SQLite service index, the curated generic-services overlay, and a library with no
     * services at all — so that a diff limited to the first half is evidence and not an assumption.
     */
    private static final List<String> LIBRARIES = List.of(
            // Schema-driven: an LS-bundled trigger-metadata.json resolves for each of these.
            "ballerina/http",
            "ballerina/graphql",
            "ballerina/grpc",
            "ballerina/websocket",
            "ballerina/ftp",
            "ballerina/smb",
            "ballerina/mcp",
            "ballerina/websub",
            "ballerinax/kafka",
            "ballerinax/rabbitmq",
            "ballerinax/mssql",
            "ballerinax/trigger.github",
            "ballerinax/trigger.google.calendar",
            // Bundled as part of the m2 migration, so it has no counterpart in the before render. Its
            // package also defeats an unguarded type walk (see FunctionDataBuilder.allMembers), which is
            // why it could not have been dumped before that guard existed.
            "ballerinax/sap.jco",
            // Controls: no trigger-metadata document resolves, so these must be identical across the change.
            "ballerinax/salesforce",
            "ballerinax/asb",
            "ballerina/ai",
            "ballerina/log",
            // Further controls, covering the shapes the trigger corpus does not exercise: a client-only
            // connector with no services at all, the two SQL drivers whose listeners are cdc's rather than
            // their own, the cdc module that owns the service type `mssql` borrows, an AI provider, and a
            // large generated connector. None resolves a trigger-metadata document, so all must be
            // unaffected — which is what makes them worth rendering.
            "ballerina/io",
            "ballerina/sql",
            "ballerinax/mysql",
            "ballerinax/postgresql",
            "ballerinax/cdc",
            "ballerinax/ai.azure",
            "ballerinax/github");

    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().create();

    private CopilotRenderDump() {
        // Prevent instantiation
    }

    /**
     * @param args {@code [0]} output directory; {@code [1]} optional comma-separated library override;
     *             {@code [2]} optional path to a previous run's {@code _versions.json} to compare against;
     *             {@code [3]} optional {@code lib=version,lib=version} pins, so a run can reproduce an
     *             earlier one's package versions rather than resolving latest
     * @throws IOException if the output directory cannot be written
     */
    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("usage: CopilotRenderDump <outputDir> [lib1,lib2,...] [previousVersionsJson]"
                    + " [lib=version,lib=version]");
            System.exit(2);
        }
        Path outputDir = Path.of(args[0]);
        Files.createDirectories(outputDir);

        List<String> libraries = args.length >= 2 && !args[1].isBlank()
                ? List.of(args[1].split(","))
                : LIBRARIES;

        // Pins make a run reproducible: without them a release landing between two runs shows up as a
        // catalog difference no code change caused, which is indistinguishable from a regression.
        Map<String, String> pins = new LinkedHashMap<>();
        if (args.length >= 4 && !args[3].isBlank()) {
            for (String entry : args[3].split(",")) {
                int eq = entry.indexOf('=');
                if (eq > 0) {
                    pins.put(entry.substring(0, eq).trim(), entry.substring(eq + 1).trim());
                }
            }
            System.out.println("[dump] pinned versions: " + pins);
        }

        Map<String, String> resolvedVersions = new TreeMap<>();
        List<String> report = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        for (String libraryName : libraries) {
            System.out.println("[dump] " + libraryName);
            try {
                String pin = pins.get(libraryName);
                if (pin != null) {
                    resolvedVersions.put(libraryName, pin);
                } else {
                    resolveVersion(libraryName).ifPresent(v -> resolvedVersions.put(libraryName, v));
                }

                List<Library> loaded = new CopilotLibraryManager()
                        .loadFilteredLibraries(new String[]{libraryName}, pins);
                JsonArray json = ModelToJsonConverter.librariesToJson(loaded);
                Files.writeString(outputDir.resolve(fileName(libraryName) + ".json"),
                        PRETTY.toJson(json) + "\n", StandardCharsets.UTF_8);
                report.add(summarize(libraryName, json));
            } catch (Throwable e) {
                // Throwable, not RuntimeException: ballerinax/sap.jco throws StackOverflowError out of the
                // compiler API, and an Error escaping here would kill the JVM and lose every library after
                // this one — turning one broken package into an empty run.
                String message = libraryName + ": FAILED " + e;
                System.err.println("[dump] " + message);
                e.printStackTrace(System.err);
                failures.add(message);
                report.add(message);
            }
        }

        Files.writeString(outputDir.resolve("_versions.json"),
                PRETTY.toJson(resolvedVersions) + "\n", StandardCharsets.UTF_8);

        if (args.length >= 3 && !args[2].isBlank()) {
            report.addAll(compareVersions(Path.of(args[2]), resolvedVersions));
        }
        if (!failures.isEmpty()) {
            report.add("");
            report.add("FAILURES: " + failures.size());
        }
        Files.writeString(outputDir.resolve("_dump-report.txt"),
                String.join("\n", report) + "\n", StandardCharsets.UTF_8);
        System.out.println(String.join("\n", report));

        // A library that failed to load would silently render as an absent file, which reads exactly like a
        // library the corpus never covered. Fail the task instead.
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }

    /**
     * The package version actually resolved, recorded so the before and after runs can be shown to have
     * read the same sources. Resolution failure is not fatal here — the dump itself resolves independently
     * and will report its own error.
     */
    private static Optional<String> resolveVersion(String libraryName) {
        String[] parts = libraryName.split("/");
        if (parts.length != 2) {
            return Optional.empty();
        }
        try {
            Optional<Package> pkg = PackageUtil.getModulePackage(PackageUtil.getSampleProject(), parts[0], parts[1]);
            return pkg.map(p -> p.packageVersion().toString());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Reports every library whose resolved version differs from the earlier run's. */
    private static List<String> compareVersions(Path previousFile, Map<String, String> current) {
        List<String> lines = new ArrayList<>();
        lines.add("");
        lines.add("--- package version comparison vs " + previousFile + " ---");
        try {
            JsonObject previous = PRETTY.fromJson(Files.readString(previousFile, StandardCharsets.UTF_8),
                    JsonObject.class);
            Map<String, String> drifted = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : current.entrySet()) {
                JsonElement before = previous.get(entry.getKey());
                if (before == null) {
                    drifted.put(entry.getKey(), "(absent before) -> " + entry.getValue());
                } else if (!before.getAsString().equals(entry.getValue())) {
                    drifted.put(entry.getKey(), before.getAsString() + " -> " + entry.getValue());
                }
            }
            if (drifted.isEmpty()) {
                lines.add("OK: every library resolved to the same version as the previous run.");
            } else {
                lines.add("WARNING: " + drifted.size() + " library/libraries resolved to a DIFFERENT version.");
                lines.add("Rendering differences for these are not attributable to the code change alone:");
                drifted.forEach((lib, change) -> lines.add("  " + lib + ": " + change));
            }
        } catch (IOException | RuntimeException e) {
            lines.add("could not compare: " + e);
        }
        return lines;
    }

    private static String summarize(String libraryName, JsonArray json) {
        if (json.isEmpty()) {
            return libraryName + ": EMPTY (library excluded or not resolvable)";
        }
        JsonObject library = json.get(0).getAsJsonObject();
        return "%s: annotations=%d typeDefs=%d clients=%d functions=%d services=%d readme=%s instructions=%s"
                .formatted(libraryName,
                        size(library, "annotations"),
                        size(library, "typeDefs"),
                        size(library, "clients"),
                        size(library, "functions"),
                        size(library, "services"),
                        library.has("readme"),
                        library.has("instructions"));
    }

    private static int size(JsonObject library, String key) {
        return library.has(key) && library.get(key).isJsonArray() ? library.getAsJsonArray(key).size() : 0;
    }

    /** {@code ballerinax/trigger.github} to {@code ballerinax_trigger.github}, so it is a legal file name. */
    private static String fileName(String libraryName) {
        return libraryName.replace('/', '_');
    }
}
