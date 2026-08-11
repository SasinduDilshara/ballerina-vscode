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

package io.ballerina.modelgenerator.commons.trigger;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import io.ballerina.modelgenerator.commons.ModuleInfo;
import io.ballerina.modelgenerator.commons.PackageUtil;
import io.ballerina.modelgenerator.commons.trigger.models.TriggerMetadataModel;
import io.ballerina.modelgenerator.commons.trigger.models.TriggerUISchemaModel;
import io.ballerina.modelgenerator.commons.trigger.utils.TriggerMetadataGson;
import io.ballerina.projects.Package;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * The abstract, connector-agnostic entry point for reading the trigger model family. Any LS extension
 * that needs one of these -- today service-model-generator-ls-extension, in the future
 * flow-model-generator-ls-extension's copilot surface, or any extension after that -- calls this class
 * rather than resolving a connector's package or reading its shipped JSON itself; that resolution and
 * parsing is this class's job alone, not a caller's.
 *
 * <p>Exposes exactly three reads, each keyed by {@link ModuleInfo} and returning an {@code Optional}:
 * <ul>
 *   <li>{@link #getTriggerMetadataModel} -- a connector's own shipped
 *       {@code resources/trigger-metadata.json} (the authoring-rules overlay), resolved from its
 *       {@code .bala}.</li>
 *   <li>{@link #getTriggerUISchemaModel} -- a connector's own {@code resources/trigger-ui-schema.json}
 *       (the full UI-ready form/handler tree), resolved from its {@code .bala}. Reading a connector's
 *       own UI schema bundled directly in the LS jar (as opposed to shipped by the connector itself) is
 *       deliberately not this class's job: that curated, per-connector registry
 *       ({@code bundled_trigger_models.json}) is specific to the schema-driven trigger feature and stays
 *       in service-model-generator-ls-extension's own {@code ConnectorModelReader}.</li>
 *   <li>{@link #getPackagedTriggerMetadataModel} -- the LS's own bundled
 *       {@code trigger-metadata-models/<moduleName>/trigger-metadata.json} classpath resource, for
 *       modules whose metadata is curated directly into this jar rather than shipped by the connector.
 *       Independent of {@link #getTriggerMetadataModel} -- a caller decides for itself whether/how to
 *       combine the two, this class does not silently prefer one over the other.</li>
 * </ul>
 *
 * <p>A connector's package root is resolved via
 * {@link PackageUtil#getModulePackage(io.ballerina.projects.directory.BuildProject, String, String)}'s
 * version-less overload (org + module name only) and cached by that pair, since both connector-owned
 * reads may need the same root; the packaged classpath lookup needs no package resolution at all and is
 * cached separately, keyed by bare module name.
 *
 * @since 1.10.0
 */
public final class LibraryMetadataReader {

    private static final Logger LOGGER = Logger.getLogger(LibraryMetadataReader.class.getName());

    private static final String TRIGGER_METADATA_RESOURCE_PATH = "resources/trigger-metadata.json";
    private static final String TRIGGER_UI_SCHEMA_RESOURCE_PATH = "resources/trigger-ui-schema.json";
    private static final String PACKAGED_TRIGGER_METADATA_ROOT = "trigger-metadata-models";
    private static final String PACKAGED_TRIGGER_METADATA_FILE = "trigger-metadata.json";

    private static final LibraryMetadataReader INSTANCE = new LibraryMetadataReader();

    // Shared by getTriggerMetadataModel/getTriggerUISchemaModel -- both may resolve the same connector
    // package root, so a repeated lookup pays bala-cache resolution at most once per module. Kept
    // separate from TriggerArtifactResolver's own PACKAGE_ROOT_CACHE (icon resolution): the two are
    // read on unrelated schedules and neither needs the other's cache.
    private final Map<String, Optional<Path>> packageRootCache = new ConcurrentHashMap<>();
    private final Map<String, Optional<TriggerMetadataModel>> packagedMetadataCache = new ConcurrentHashMap<>();

    // A connector's trigger-ui-schema.json carries no TypeRef-or-union slots (unlike
    // trigger-metadata.json), so it needs no custom deserializer -- a plain Gson suffices, matching
    // ConnectorModelReader's existing plain-Gson parse of the same shape.
    private final Gson plainGson = new Gson();

    private LibraryMetadataReader() {
    }

    public static LibraryMetadataReader getInstance() {
        return INSTANCE;
    }

    /**
     * Why a read produced no usable document — three failures, not one absence.
     *
     * <p>Collapsing them into an empty {@link Optional} is what let two real defects hide. A caller that
     * cannot tell "this package ships no metadata" from "this package ships metadata I must not read" will
     * substitute something else for the latter, and the something else is always worse than nothing: a
     * <b>stale bundled copy</b> describing a release the package no longer matches, or a poorer catalog
     * presented with no hint that a richer one was rejected. Only the caller can decide what to do, so the
     * distinction is reported rather than swallowed.
     */
    public enum MetadataOutcome {
        /** The package ships no {@code resources/trigger-metadata.json} at all. */
        ABSENT,
        /** A document was read and this build implements its version. */
        USABLE,
        /** A document is present, but declares a spec major version this build does not implement. */
        UNSUPPORTED_VERSION,
        /** A document is present, but could not be parsed. */
        MALFORMED
    }

    /**
     * One document read, with the reason when there is nothing usable.
     *
     * @param document the parsed document, or {@code null} unless {@code outcome} is
     *                 {@link MetadataOutcome#USABLE}
     * @param outcome  what happened
     */
    public record MetadataRead(TriggerMetadataModel document, MetadataOutcome outcome) {

        private static final MetadataRead ABSENT = new MetadataRead(null, MetadataOutcome.ABSENT);

        static MetadataRead absent() {
            return ABSENT;
        }

        static MetadataRead of(TriggerMetadataModel document) {
            return new MetadataRead(document, MetadataOutcome.USABLE);
        }

        static MetadataRead failed(MetadataOutcome outcome) {
            return new MetadataRead(null, outcome);
        }

        /** The document when it may be used, so an indifferent caller keeps its one-liner. */
        public Optional<TriggerMetadataModel> usable() {
            return Optional.ofNullable(document);
        }

        /**
         * Whether a document was <b>there</b>, whatever came of reading it.
         *
         * <p>This is the question a fallback has to ask: a package that ships a document it cannot serve
         * must not be quietly served someone else's.
         *
         * @return whether the package ships a document
         */
        public boolean present() {
            return outcome != MetadataOutcome.ABSENT;
        }
    }

    /** The connector's own {@code resources/trigger-metadata.json}, resolved from its {@code .bala}. */
    public Optional<TriggerMetadataModel> getTriggerMetadataModel(ModuleInfo moduleInfo) {
        return packageRoot(moduleInfo).map(this::readTriggerMetadataModel)
                .flatMap(MetadataRead::usable);
    }

    /**
     * The connector's own {@code resources/trigger-metadata.json}, read from a package the caller has
     * already resolved. The same document {@link #getTriggerMetadataModel(ModuleInfo)} returns; use
     * this wherever the caller already holds the {@link Package}, so no second — and potentially
     * network-bound — resolution is paid. That matters for callers that consult this for every
     * library rather than a curated few: the read then costs one {@code stat}.
     *
     * <p>Deliberately not an overload of {@link #getTriggerMetadataModel(ModuleInfo)}: both take an
     * unrelated reference type, so a {@code null} argument would be ambiguous at every call site.
     *
     * @param pkg the already-resolved package (may be {@code null})
     * @return the parsed document, or empty when the package ships none
     */
    public Optional<TriggerMetadataModel> getShippedTriggerMetadataModel(Package pkg) {
        return readShippedTriggerMetadata(pkg).usable();
    }

    /**
     * {@link #getShippedTriggerMetadataModel(Package)} plus <i>why</i> there is no usable document.
     *
     * <p>Use this wherever an empty result would otherwise be answered by substituting a different document.
     * A connector shipping its own metadata is versioned with itself and therefore authoritative: if its
     * document cannot be read, no other document describes the release the caller actually resolved, and
     * quietly serving the LS's bundled copy states a contract the package no longer honours.
     *
     * @param pkg the already-resolved package (may be {@code null})
     * @return the read, never {@code null}
     */
    public MetadataRead readShippedTriggerMetadata(Package pkg) {
        if (pkg == null) {
            return MetadataRead.absent();
        }
        try {
            return readTriggerMetadataModel(pkg.project().sourceRoot());
        } catch (Throwable e) {
            // Throwable, and still non-propagating: callers sit on hot request paths, and a broken package
            // must not take a request down.
            //
            // **ABSENT, not MALFORMED.** {@link #readTriggerMetadataModel} already classifies the two
            // outcomes that describe the document itself — no file is ABSENT, unparseable JSON is MALFORMED
            // — so anything reaching here failed BEFORE the document's content was ever in question:
            // resolving the source root, a security manager refusing the read, an invalid path. "We could not
            // even look" is much closer to "there is no document" than to "the document is broken", and
            // getting that wrong is now expensive rather than cosmetic: MALFORMED makes `present()` true,
            // which tells the caller to suppress BOTH the bundled document and the service index. One
            // unrelated exception would then cost the library its whole service catalog, where before this
            // tri-state existed it cost nothing at all.
            //
            // Logged either way, since a silent failure here is what R2 set out to end.
            LOGGER.warning("Could not read " + TRIGGER_METADATA_RESOURCE_PATH + " from "
                    + pkg.packageOrg().value() + "/" + pkg.packageName().value()
                    + "; treating the package as shipping no metadata: " + e);
            return MetadataRead.absent();
        }
    }

    /** The connector's own {@code resources/trigger-ui-schema.json}, resolved from its {@code .bala}. */
    public Optional<TriggerUISchemaModel> getTriggerUISchemaModel(ModuleInfo moduleInfo) {
        return packageRoot(moduleInfo).flatMap(this::readTriggerUISchemaModel);
    }

    /**
     * The LS's bundled {@code trigger-metadata-models/<moduleName>/trigger-metadata.json} classpath
     * resource, if any. Keyed by bare module name only -- this is a small, curated set the LS ships
     * directly, so no org/version is needed to disambiguate (mirrors {@code TriggerArtifactReader}).
     */
    public Optional<TriggerMetadataModel> getPackagedTriggerMetadataModel(ModuleInfo moduleInfo) {
        if (moduleInfo == null || moduleInfo.moduleName() == null) {
            return Optional.empty();
        }
        return packagedMetadataCache.computeIfAbsent(moduleInfo.moduleName(), this::readPackagedMetadata);
    }

    private Optional<TriggerMetadataModel> readPackagedMetadata(String moduleName) {
        String resourcePath = PACKAGED_TRIGGER_METADATA_ROOT + "/" + moduleName + "/"
                + PACKAGED_TRIGGER_METADATA_FILE;
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                return Optional.empty();
            }
            String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            return gated(TriggerMetadataGson.instance().fromJson(json, TriggerMetadataModel.class),
                    resourcePath).usable();
        } catch (IOException | JsonParseException e) {
            // A bundled document is this repo's own, and TriggerMetadataCorpusTest validates every one of
            // them, so a failure here is a build defect rather than a third party's typo. Logged all the
            // same: silence is what made the shipped-document equivalent undiagnosable.
            LOGGER.warning("Ignoring bundled " + resourcePath + ": " + e);
            return Optional.empty();
        }
    }

    /**
     * Applies the spec's top-level {@code version} gate to a freshly-parsed document.
     *
     * <p>Every read goes through here, so a document declaring a version this build does not implement can
     * never reach a consumer by a path that forgot to check.
     *
     * <p>A rejected document reports {@link MetadataOutcome#UNSUPPORTED_VERSION} rather than an absence,
     * because those two demand opposite things of a caller: an absence may be filled from elsewhere, a
     * rejection may not. The log line no longer claims what the caller will do — it used to say "Falling
     * back to the service index", which was simply untrue on the shipped-document path, where the fallback
     * was the LS's own bundled copy of a document the connector had superseded.
     *
     * @param document the parsed document; may be {@code null}
     * @param source   what was read, for the log line
     * @return the read outcome
     */
    private MetadataRead gated(TriggerMetadataModel document, String source) {
        if (document == null) {
            // Valid JSON that deserialized to nothing — `null`, or a bare literal. There is a file, so this
            // is a defect in it rather than an absence.
            LOGGER.warning("Ignoring " + source + ": it parsed to no document.");
            return MetadataRead.failed(MetadataOutcome.MALFORMED);
        }
        SpecVersionGate.VersionVerdict verdict = SpecVersionGate.evaluate(document);
        if (verdict == SpecVersionGate.VersionVerdict.REJECT) {
            LOGGER.warning("Ignoring " + source + ": it declares spec version '" + document.version()
                    + "', which this build does not implement (expected major "
                    + SpecVersionGate.MAJOR_V1 + ", e.g. '" + SpecVersionGate.VERSION_V1 + "').");
            return MetadataRead.failed(MetadataOutcome.UNSUPPORTED_VERSION);
        }
        if (verdict == SpecVersionGate.VersionVerdict.ACCEPT_WITH_WARNING) {
            LOGGER.fine(() -> source + " declares no spec `version`; reading it as '"
                    + SpecVersionGate.VERSION_V1 + "'.");
        }
        return MetadataRead.of(document);
    }

    /**
     * Resolves and parses {@code resources/trigger-metadata.json} relative to {@code packageRoot}.
     *
     * <p>Package-private rather than private, purely as a test seam. Reading the JSON off a resolved package
     * is still this class's own job and never a caller's — the two public entry points are the API — but the
     * three not-usable outcomes cannot otherwise be exercised at all: no package published to Central ships a
     * {@code resources/trigger-metadata.json} today, so a test that went in through {@link Package} would
     * have nothing to read. That gap is why the shipped-document path had no test before this.
     */
    MetadataRead readTriggerMetadataModel(Path packageRoot) {
        Optional<String> json = readResourceFile(packageRoot, TRIGGER_METADATA_RESOURCE_PATH);
        if (json.isEmpty()) {
            return MetadataRead.absent();
        }
        String source = packageRoot.resolve(TRIGGER_METADATA_RESOURCE_PATH).toString();
        try {
            return gated(TriggerMetadataGson.instance().fromJson(json.get(), TriggerMetadataModel.class),
                    source);
        } catch (JsonParseException e) {
            // Logged, not silent. This is the one signal a connector author gets that their file has a typo,
            // and swallowing it made a malformed document indistinguishable from no document at all.
            LOGGER.warning("Ignoring " + source + ": it is not valid trigger metadata: " + e.getMessage());
            return MetadataRead.failed(MetadataOutcome.MALFORMED);
        }
    }

    /** {@code Path}-rooted counterpart of {@link #readTriggerMetadataModel}, for the UI-schema shape. */
    private Optional<TriggerUISchemaModel> readTriggerUISchemaModel(Path packageRoot) {
        return readResourceFile(packageRoot, TRIGGER_UI_SCHEMA_RESOURCE_PATH).flatMap(json -> {
            try {
                return Optional.ofNullable(plainGson.fromJson(json, TriggerUISchemaModel.class));
            } catch (JsonParseException e) {
                return Optional.empty();
            }
        });
    }

    /**
     * Resolves a connector's package root by {@code org}/{@code moduleName} alone, cached by that pair.
     * Wrapped in a blanket {@code catch (Throwable)}: {@link PackageUtil#getModulePackage}'s version-less
     * overload falls through to a live Central version lookup on an offline-metadata miss, which
     * <b>throws</b> (rather than returning empty) for an org/module that doesn't exist there or when
     * offline -- any such failure must degrade to "no metadata," not propagate to the caller.
     */
    private Optional<Path> packageRoot(ModuleInfo moduleInfo) {
        if (moduleInfo == null || moduleInfo.org() == null || moduleInfo.moduleName() == null) {
            return Optional.empty();
        }
        String key = moduleInfo.org() + "/" + moduleInfo.moduleName();
        return packageRootCache.computeIfAbsent(key, ignored -> {
            try {
                Optional<Package> pkg = PackageUtil.getModulePackage(PackageUtil.getSampleProject(),
                        moduleInfo.org(), moduleInfo.moduleName());
                return pkg.map(aPackage -> aPackage.project().sourceRoot());
            } catch (Throwable e) {
                return Optional.empty();
            }
        });
    }

    /** Reads a package-relative file as UTF-8 text, guarding against it escaping {@code packageRoot}. */
    private Optional<String> readResourceFile(Path packageRoot, String relativePath) {
        Path file = packageRoot.resolve(relativePath).normalize();
        if (!file.startsWith(packageRoot) || !Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
