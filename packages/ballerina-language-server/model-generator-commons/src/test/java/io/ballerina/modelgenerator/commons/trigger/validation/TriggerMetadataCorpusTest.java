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

package io.ballerina.modelgenerator.commons.trigger.validation;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.ballerina.modelgenerator.commons.trigger.models.TriggerMetadataModel;
import io.ballerina.modelgenerator.commons.trigger.utils.TriggerMetadataGson;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs the validator over every {@code trigger-metadata.json} this repo ships.
 *
 * <p>This is the test the whole validation tier exists for. The consuming pipeline degrades gracefully
 * around every defect these checks find — that is correct at request time and is exactly why the defects
 * were invisible. Here they fail the build.
 *
 * @since 1.10.0
 */
public class TriggerMetadataCorpusTest {

    /** Every bundled document, by module key. */
    private static final List<String> CORPUS = List.of(
            "ftp", "graphql", "grpc", "http", "kafka", "mcp", "mssql.cdc", "rabbitmq", "smb",
            "trigger.github", "trigger.google.calendar", "websocket", "websub");

    /** The schema this repo ships, which every bundled document is validated against. */
    private static final String SCHEMA_RESOURCE = "schemas/trigger-metadata.schema.json";

    @Test
    public void testEveryBundledDocumentIsFreeOfErrors() {
        Map<String, List<Finding>> errorsByDocument = new LinkedHashMap<>();
        for (String key : CORPUS) {
            List<Finding> errors =
                    TriggerMetadataValidator.validate(parse(key), Finding.Severity.ERROR);
            if (!errors.isEmpty()) {
                errorsByDocument.put(key, errors);
            }
        }
        Assert.assertTrue(errorsByDocument.isEmpty(), "Documents with ERROR findings:\n" + render(errorsByDocument));
    }

    @Test
    public void testEveryBundledDocumentParses() {
        // A document that fails to deserialize reaches the pipeline as "this library ships no metadata",
        // which is indistinguishable from the (very common) case of a library that genuinely ships none.
        for (String key : CORPUS) {
            TriggerMetadataModel document = parse(key);
            Assert.assertNotNull(document, key + " failed to parse");
            Assert.assertNotNull(document.listeners(), key + " parsed with no listeners");
            Assert.assertNotNull(document.serviceTypes(), key + " parsed with no serviceTypes");
        }
    }

    /**
     * Validates every bundled document against the shipped schema, <b>reading the schema itself</b>.
     *
     * <p>This used to compare each document against two hand-written {@code Set}s mirroring the schema's
     * top-level clauses. That mirror was the problem it was meant to solve: it covered only the top level,
     * and nothing made it agree with the file it claimed to mirror — a schema edit and a stale copy of it
     * would have looked identical from here.
     *
     * <p>{@link SchemaWalk} enforces the three clauses that actually bite — {@code required},
     * {@code additionalProperties: false} and {@code enum} — recursively, resolving {@code $ref} within the
     * document. No JSON-schema library is added: one is not on this build's classpath and pulling one in
     * would break every {@code --offline} build that has not cached it.
     */
    @Test
    public void testEveryBundledDocumentSatisfiesTheShippedSchema() {
        JsonObject schema = readJson(SCHEMA_RESOURCE);
        Map<String, List<String>> violationsByDocument = new LinkedHashMap<>();
        for (String key : CORPUS) {
            List<String> violations = SchemaWalk.validate(schema, raw(key));
            if (!violations.isEmpty()) {
                violationsByDocument.put(key, violations);
            }
        }
        Assert.assertTrue(violationsByDocument.isEmpty(),
                "Documents violating the shipped schema:\n" + renderViolations(violationsByDocument));
    }

    /**
     * The walker is only worth trusting if it can fail, so this proves it does.
     *
     * <p>A test that validates thirteen conformant documents passes just as well when the validator is a
     * no-op. These three mutations are one per clause the walker enforces.
     */
    @Test
    public void testTheSchemaWalkerRejectsWhatTheSchemaForbids() {
        JsonObject schema = readJson(SCHEMA_RESOURCE);

        JsonObject missingRequired = raw("kafka");
        missingRequired.remove("version");
        Assert.assertFalse(SchemaWalk.validate(schema, missingRequired).isEmpty(),
                "a document missing a required key must be rejected");

        JsonObject unknownKey = raw("kafka");
        unknownKey.addProperty("notAThing", true);
        Assert.assertFalse(SchemaWalk.validate(schema, unknownKey).isEmpty(),
                "additionalProperties: false must reject an unknown key");

        // Reached only through two `$ref` hops (serviceTypes -> serviceType -> handlers), so this also
        // proves reference resolution works — a walker that silently failed to follow `$ref` would
        // enforce nothing below the top level and still pass every other assertion here.
        JsonObject badEnum = raw("kafka");
        badEnum.getAsJsonArray("serviceTypes").get(0).getAsJsonObject()
                .getAsJsonObject("handlers").addProperty("addMode", "someFutureMode");
        Assert.assertFalse(SchemaWalk.validate(schema, badEnum).isEmpty(),
                "a value outside an enum must be rejected");

        // `version` is deliberately NOT used for this: the schema constrains it only as `type: string`
        // with an `examples` list, so `v99` is schema-valid. It is the SpecVersionGate that rejects an
        // unimplemented version, not the schema — asserting otherwise here would pin a constraint the
        // schema does not make.
    }

    @Test
    public void testEverySpecSectionHasAtLeastOneRegisteredCheck() {
        // The validator half of the plan's traceability guard: a construct cannot be half-covered, with a
        // resolver that reads it and no check that validates it.
        //
        // §7 is in this list even though no check declares it as its own section: `params[]` is validated
        // by TypeRefCheck (§1 — an empty `type` array leaves the slot with no signature) and by
        // VocabularyCheck (§10 — `presence` and `addMode` against the spec's tables). Both are filed under
        // the section that owns the *rule*, not the one that owns the construct, so the assertion below
        // maps §7 onto them explicitly rather than leaving the gap unexplained.
        List<String> owned = TriggerMetadataValidator.checks().stream()
                .map(DocumentCheck::specSection).distinct().toList();
        for (String section : List.of("§1", "§2", "§3", "§4", "§5", "§6", "§8", "§9", "§10")) {
            Assert.assertTrue(owned.contains(section),
                    "no registered check owns spec " + section + "; owned: " + owned);
        }
        List<String> paramCheckIds = TriggerMetadataValidator.checks().stream()
                .map(DocumentCheck::id).filter(id -> id.equals("typeRef") || id.equals("vocabulary"))
                .toList();
        Assert.assertEquals(paramCheckIds.size(), 2,
                "spec §7 is covered by typeRef and vocabulary; if either is renamed or removed, §7 loses "
                        + "its coverage silently. Registered: " + paramCheckIds);
    }

    @Test
    public void testEveryCheckIsAttributable() {
        List<String> ids = new ArrayList<>();
        for (DocumentCheck check : TriggerMetadataValidator.checks()) {
            Assert.assertNotNull(check.id());
            Assert.assertFalse(check.id().isBlank());
            Assert.assertTrue(check.specSection().startsWith("§"),
                    check.id() + " must name the spec section it owns, got: " + check.specSection());
            ids.add(check.id());
        }
        Assert.assertEquals(ids.stream().distinct().count(), ids.size(),
                "check ids must be unique: " + ids);
    }

    /**
     * Every warning the corpus is known to carry, as {@code document|checkId|path}.
     *
     * <p>All five are <b>spec limitations, not document defects</b>, which is why they are tolerated rather
     * than fixed:
     * <ul>
     *   <li>{@code grpc} and {@code graphql} both need "an open-ended catalog whose handlers take one of N
     *       shapes" — gRPC's four RPC shapes with proto-derived names, GraphQL's query/mutation/
     *       subscription. Spec §4 allows exactly one {@code "*"} entry, so neither can be expressed.
     *       Editing either document to conform would make the rendered output worse: gRPC's four shape
     *       names would become literal, copyable handler names.</li>
     *   <li>{@code graphql}'s mutation carries {@code fieldName}/{@code graphqlOperation} on a
     *       {@code remote} handler, which is correct Ballerina — see {@link ResourceExtrasCheck}.</li>
     *   <li>{@code websocket}'s {@code Service} is genuinely not listener-attachable.</li>
     * </ul>
     *
     * <p>Pinned exactly so a <i>new</i> warning cannot hide among the accepted ones.
     */
    private static final Set<String> ACCEPTED_WARNINGS = Set.of(
            "graphql|addMode|serviceTypes[0].handlers.options",
            "graphql|resourceExtras|serviceTypes[0].handlers.options[1].fieldName",
            "graphql|resourceExtras|serviceTypes[0].handlers.options[1].graphqlOperation",
            "grpc|addMode|serviceTypes[0].handlers",
            "websocket|listenerRef|serviceTypes[service]");

    @Test
    public void testTheCorpusCarriesExactlyTheKnownWarnings() {
        List<String> actual = new ArrayList<>();
        for (String key : CORPUS) {
            for (Finding finding : TriggerMetadataValidator.validate(parse(key), Finding.Severity.WARN)) {
                actual.add(key + "|" + finding.checkId() + "|" + finding.path());
            }
        }
        List<String> unexpected = actual.stream().filter(w -> !ACCEPTED_WARNINGS.contains(w)).sorted().toList();
        Assert.assertTrue(unexpected.isEmpty(), "new warnings the corpus did not carry before: " + unexpected);

        List<String> resolved = ACCEPTED_WARNINGS.stream().filter(w -> !actual.contains(w)).sorted().toList();
        Assert.assertTrue(resolved.isEmpty(),
                "these warnings no longer fire; remove them from ACCEPTED_WARNINGS: " + resolved);
    }

    @Test
    public void testTheCorpusIsTheExpectedSize() {
        // Pins the count so a document added without being validated fails here rather than silently
        // skipping every check.
        Assert.assertEquals(CORPUS.size(), 13);
    }

    // ---- helpers --------------------------------------------------------------------

    private static String resourcePath(String key) {
        return "trigger-metadata-models/" + key + "/trigger-metadata.json";
    }

    private static String read(String key) {
        try (InputStream is = TriggerMetadataCorpusTest.class.getClassLoader()
                .getResourceAsStream(resourcePath(key))) {
            Assert.assertNotNull(is, "missing bundled document: " + resourcePath(key));
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static TriggerMetadataModel parse(String key) {
        return TriggerMetadataGson.instance().fromJson(read(key), TriggerMetadataModel.class);
    }

    private static JsonObject raw(String key) {
        return readJson(resourcePath(key));
    }

    /** Any JSON resource on the test classpath, parsed. Used for the documents and for the schema. */
    private static JsonObject readJson(String resource) {
        try (InputStreamReader reader = new InputStreamReader(
                TriggerMetadataCorpusTest.class.getClassLoader()
                        .getResourceAsStream(resource), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String renderViolations(Map<String, List<String>> violationsByDocument) {
        StringBuilder sb = new StringBuilder();
        violationsByDocument.forEach((key, violations) -> {
            sb.append("  ").append(key).append(":\n");
            violations.forEach(violation -> sb.append("    ").append(violation).append('\n'));
        });
        return sb.toString();
    }

    private static String render(Map<String, List<Finding>> errorsByDocument) {
        StringBuilder sb = new StringBuilder();
        errorsByDocument.forEach((key, findings) -> {
            sb.append("  ").append(key).append(":\n");
            findings.forEach(finding -> sb.append("    ").append(finding).append('\n'));
        });
        return sb.toString();
    }
}
