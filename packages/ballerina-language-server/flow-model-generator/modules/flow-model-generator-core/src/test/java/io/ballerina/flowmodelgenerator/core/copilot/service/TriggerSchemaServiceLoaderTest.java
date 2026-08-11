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

package io.ballerina.flowmodelgenerator.core.copilot.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.ballerina.modelgenerator.commons.trigger.LibraryMetadataReader;
import io.ballerina.modelgenerator.commons.trigger.models.TriggerMetadataModel;
import io.ballerina.modelgenerator.commons.trigger.models.TriggerMetadataModel.ServiceType.HandlerOption;
import io.ballerina.modelgenerator.commons.trigger.models.TriggerMetadataModel.ServiceType.Param;
import io.ballerina.modelgenerator.commons.trigger.models.TypeRef;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Tests for {@link TriggerSchemaServiceLoader}'s pure rendering logic: metadata {@code TypeRef} →
 * service-index-form signature strings, return-union joining, and the small string helpers.
 *
 * @since 1.7.0
 */
public class TriggerSchemaServiceLoaderTest {

    private static final Predicate<String> KAFKA_TYPES =
            Set.of("AnydataConsumerRecord", "BytesConsumerRecord", "Caller", "Error", "Listener")::contains;
    private static final Predicate<String> NONE = name -> false;

    // ---- renderTypeRef -----------------------------------------------------------

    @Test
    public void testRenderTypeRefNull() {
        Assert.assertEquals(TriggerSchemaServiceLoader.renderTypeRef(null, "kafka", KAFKA_TYPES), "");
        Assert.assertEquals(TriggerSchemaServiceLoader.renderTypeRef(
                new TypeRef(null, null), "kafka", KAFKA_TYPES), "");
    }

    @Test
    public void testRenderTypeRefBuiltinsStayBare() {
        Assert.assertEquals(render("json"), "json");
        Assert.assertEquals(render("string[][]"), "string[][]");
        Assert.assertEquals(render("record {}"), "record {}");
        Assert.assertEquals(render("stream<string[], error?>"), "stream<string[], error?>");
        Assert.assertEquals(render("()"), "()");
        Assert.assertEquals(render("error"), "error");
        Assert.assertEquals(render("anydata"), "anydata");
    }

    @Test
    public void testRenderTypeRefModuleDeclaredTypesGetAliasPrefix() {
        Assert.assertEquals(render("AnydataConsumerRecord[]"), "kafka:AnydataConsumerRecord[]");
        Assert.assertEquals(render("Caller"), "kafka:Caller");
        Assert.assertEquals(render("Error"), "kafka:Error");
    }

    @Test
    public void testRenderTypeRefSubmoduleUsesAlias() {
        Predicate<String> githubTypes = Set.of("ListenerConfig", "IssuesEvent")::contains;
        Assert.assertEquals(TriggerSchemaServiceLoader.renderTypeRef(
                new TypeRef("IssuesEvent", null), "trigger.github", githubTypes), "github:IssuesEvent");
    }

    @Test
    public void testRenderTypeRefForeignPackageInfoUsesItsModuleAlias() {
        TypeRef cdcError = new TypeRef("Error",
                new TypeRef.PackageInfo("ballerinax", "cdc", "cdc", "1.3.2"));
        // Foreign module: prefixed with the foreign alias; TypeResolver later leaves it unlinked.
        Assert.assertEquals(TriggerSchemaServiceLoader.renderTypeRef(cdcError, "mssql", NONE), "cdc:Error");
    }

    @Test
    public void testRenderTypeRefSamePackagePackageInfoUsesOwnAlias() {
        TypeRef ownType = new TypeRef("Caller",
                new TypeRef.PackageInfo("ballerinax", "kafka", "kafka", "4.5.0"));
        Assert.assertEquals(TriggerSchemaServiceLoader.renderTypeRef(ownType, "kafka", NONE), "kafka:Caller");
    }

    @Test
    public void testRenderTypeRefForeignSubmoduleAlias() {
        TypeRef driverType = new TypeRef("Config",
                new TypeRef.PackageInfo("ballerinax", "mssql.cdc.driver", "mssql.cdc.driver", "1.0.2"));
        Assert.assertEquals(TriggerSchemaServiceLoader.renderTypeRef(driverType, "mssql", NONE), "driver:Config");
    }

    private static String render(String name) {
        return TriggerSchemaServiceLoader.renderTypeRef(new TypeRef(name, null), "kafka", KAFKA_TYPES);
    }

    // ---- renderReturns -----------------------------------------------------------

    @Test
    public void testRenderReturnsJoinsUnionMembers() {
        Assert.assertEquals(TriggerSchemaServiceLoader.renderReturns(
                List.of(new TypeRef("error", null), new TypeRef("()", null)), "kafka", NONE), "error|()");
        Assert.assertEquals(TriggerSchemaServiceLoader.renderReturns(
                List.of(new TypeRef("anydata", null), new TypeRef("error", null)), "rabbitmq", NONE),
                "anydata|error");
    }

    @Test
    public void testRenderReturnsScalarAndEmpty() {
        Assert.assertEquals(TriggerSchemaServiceLoader.renderReturns(
                List.of(new TypeRef("()", null)), "mssql", NONE), "()");
        Assert.assertEquals(TriggerSchemaServiceLoader.renderReturns(List.of(), "kafka", NONE), "");
        Assert.assertEquals(TriggerSchemaServiceLoader.renderReturns(null, "kafka", NONE), "");
    }

    // ---- helpers -------------------------------------------------------------------

    @Test
    public void testFirstTypeRefTakesCodegenDefault() {
        TypeRef first = new TypeRef("AnydataConsumerRecord[]", null);
        TypeRef second = new TypeRef("BytesConsumerRecord[]", null);
        Assert.assertSame(TriggerSchemaServiceLoader.firstTypeRef(List.of(first, second)), first);
        Assert.assertNull(TriggerSchemaServiceLoader.firstTypeRef(List.of()));
        Assert.assertNull(TriggerSchemaServiceLoader.firstTypeRef(null));
    }

    @Test
    public void testBaseIdentifier() {
        Assert.assertEquals(TriggerSchemaServiceLoader.baseIdentifier("AnydataConsumerRecord[]"),
                "AnydataConsumerRecord");
        Assert.assertEquals(TriggerSchemaServiceLoader.baseIdentifier("Caller"), "Caller");
        Assert.assertEquals(TriggerSchemaServiceLoader.baseIdentifier("record {}"), "record");
        Assert.assertNull(TriggerSchemaServiceLoader.baseIdentifier("()"));
        Assert.assertNull(TriggerSchemaServiceLoader.baseIdentifier(""));
        Assert.assertNull(TriggerSchemaServiceLoader.baseIdentifier(null));
    }

    @Test
    public void testGetAlias() {
        Assert.assertEquals(TriggerSchemaServiceLoader.getAlias("kafka"), "kafka");
        Assert.assertEquals(TriggerSchemaServiceLoader.getAlias("trigger.github"), "github");
        Assert.assertEquals(TriggerSchemaServiceLoader.getAlias("mssql.cdc.driver"), "driver");
    }

    /**
     * The bundled-key map is an alias table, not an allowlist: it exists only for libraries whose
     * bundled document is filed under a name the library itself does not carry. A library absent from
     * it is still served — either from its own shipped document, or from a bundled document filed
     * under its bare package name.
     */
    @Test
    public void testBundledMetadataKeysAliasOnlyDivergentNames() {
        Assert.assertEquals(TriggerSchemaServiceLoader.BUNDLED_METADATA_KEYS.get("ballerinax/mssql"),
                "mssql.cdc", "mssql's bundled document is filed under the CDC module name");
        Assert.assertEquals(TriggerSchemaServiceLoader.BUNDLED_METADATA_KEYS.get("ballerinax/kafka"), "kafka");
        Assert.assertNull(TriggerSchemaServiceLoader.BUNDLED_METADATA_KEYS.get("ballerinax/asb"),
                "Absence is not exclusion — asb is simply not aliased");
        Assert.assertNull(TriggerSchemaServiceLoader.BUNDLED_METADATA_KEYS.get("ballerina/http"));
    }

    @Test
    public void testLoadServicesMissingInputsYieldEmpty() {
        Assert.assertTrue(TriggerSchemaServiceLoader.loadServices("ballerinax/asb", null, null).isEmpty(),
                "Missing package/semantic model must yield empty");
        Assert.assertTrue(TriggerSchemaServiceLoader.loadServices("ballerinax/kafka", null, null).isEmpty(),
                "Missing package/semantic model must yield empty");
    }

    /**
     * An empty result carries <i>why</i> it is empty, and the caller's fallback turns on that answer.
     *
     * <p>"We never looked" must not read as "we looked and the document produced nothing": the second
     * suppresses the service index, and reporting it for a library that simply has no compiled package
     * behind it would take a working library offline. {@code ballerinax/kafka} is the sharp case — it
     * <i>does</i> ship a bundled document, so the flag cannot be inferred from the library name.
     *
     * <p>The converse state — resolved, and produced nothing — needs a compiled package whose release no
     * longer matches its document, so it has no corpus instance to assert here and is covered by the
     * end-to-end suite instead.
     */
    @Test
    public void testMissingInputsReportNoDocumentRatherThanAFailedOne() {
        Assert.assertFalse(TriggerSchemaServiceLoader.load("ballerinax/kafka", null, null).documentResolved(),
                "No package to read means no document was resolved, whatever the library is called");
        Assert.assertFalse(TriggerSchemaServiceLoader.load("ballerinax/asb", null, null).documentResolved(),
                "A library that ships no document must never suppress the service index");
    }

    // ---- buildOptionMethods ----------------------------------------------------------

    private static HandlerOption option(String name, String kind, String presence, List<Param> params,
                                        List<TypeRef> returns) {
        return new HandlerOption(name, kind, null, null, null, presence, null, null, params, returns,
                null, null);
    }

    private static Param param(String name, String type, String presence, String addMode) {
        return new Param(name, null, null, List.of(new TypeRef(type, null)), presence, addMode,
                null, null);
    }

    private static final List<TypeRef> ERROR_NIL =
            List.of(new TypeRef("error", null), new TypeRef("()", null));

    @Test
    public void testBuildOptionMethodsSkipsWildcardNullAndNamelessOptions() {
        JsonArray methods = TriggerSchemaServiceLoader.buildOptionMethods(
                java.util.Arrays.asList(
                        option("*", "remote", "optional", null, ERROR_NIL),
                        null,
                        option(null, "remote", "optional", null, ERROR_NIL)),
                "Service", NONE, "testmod");
        Assert.assertTrue(methods.isEmpty(), "Wildcard, null, and name-less options must be skipped");
        Assert.assertTrue(TriggerSchemaServiceLoader.buildOptionMethods(
                null, "Service", NONE, "testmod").isEmpty());
    }

    @Test
    public void testBuildOptionMethodsResourceKindAndNoParams() {
        JsonArray methods = TriggerSchemaServiceLoader.buildOptionMethods(
                List.of(option("chat", "resource", "required", null, ERROR_NIL)),
                "Service", NONE, "testmod");
        JsonObject method = methods.get(0).getAsJsonObject();
        Assert.assertEquals(method.get("type").getAsString(), "resource");
        Assert.assertFalse(method.has("parameters"), "No parameters key for a param-less option");
        Assert.assertFalse(method.has("description"), "No description without UI docs");
        // Spec §5: `presence` is meaningful "Only under `addMode: subset`", and this fixture is built as a
        // subset catalog — so `presence: "required"` must reach the wire as `optional: false`. It used to be
        // dropped, which made a mandatory handler indistinguishable from a skippable one.
        Assert.assertTrue(method.has("optional"),
                "A subset option's presence must be stated, not dropped");
        Assert.assertFalse(method.get("optional").getAsBoolean(),
                "presence: \"required\" is not optional");
        Assert.assertEquals(method.getAsJsonObject("return").getAsJsonObject("type")
                .get("name").getAsString(), "error?");
    }

    @Test
    public void testBuildOptionMethodsParamNamePrecedence() {
        // An authored metadata name always wins over generation, and no description is emitted for a
        // marker-type handler (neither the document nor the library has one).
        JsonArray withMetadataName = TriggerSchemaServiceLoader.buildOptionMethods(
                List.of(option("onEvent", "remote", "optional",
                        List.of(param("payload", "json", "optional", null)), ERROR_NIL)),
                "Service", NONE, "testmod");
        JsonObject method = withMetadataName.get(0).getAsJsonObject();
        Assert.assertFalse(method.has("description"), "Marker-type handlers carry no description");
        JsonObject p = method.getAsJsonArray("parameters").get(0).getAsJsonObject();
        Assert.assertEquals(p.get("name").getAsString(), "payload");
        Assert.assertFalse(p.has("description"), "Marker-type params carry no description");
        Assert.assertTrue(p.get("optional").getAsBoolean());

        // A name-less slot with a usable declared type is named from that type.
        JsonArray generated = TriggerSchemaServiceLoader.buildOptionMethods(
                List.of(option("onEvent", "remote", "optional",
                        List.of(param(null, "WatchEvent", "required", null)), null)),
                "Service", Set.of("WatchEvent")::contains, "testmod");
        JsonObject generatedParam = generated.get(0).getAsJsonObject()
                .getAsJsonArray("parameters").get(0).getAsJsonObject();
        Assert.assertEquals(generatedParam.get("name").getAsString(), "watchEvent");
        Assert.assertFalse(generatedParam.has("optional"));

        // A name-less slot whose type yields no identifier falls back positionally.
        JsonArray synthetic = TriggerSchemaServiceLoader.buildOptionMethods(
                List.of(option("onUnknown", "remote", "optional",
                        List.of(param(null, "json", "required", null)), null)),
                "Service", NONE, "testmod");
        Assert.assertEquals(synthetic.get(0).getAsJsonObject()
                .getAsJsonArray("parameters").get(0).getAsJsonObject().get("name").getAsString(), "param1");
    }

    @Test
    public void testBuildOptionMethodsSkipsHandlersWithUndeclaredTypes() {
        // Metadata authored against a future release: "HubError" is not declared by the resolved
        // package — the handler must be skipped, not rendered uncompilable.
        JsonArray skipped = TriggerSchemaServiceLoader.buildOptionMethods(
                List.of(option("onHubError", "remote", "optional",
                        List.of(param(null, "HubError", "required", null)), ERROR_NIL)),
                "SubscriberService", NONE, "websub");
        Assert.assertTrue(skipped.isEmpty());

        // Same handler with the type declared: kept, with the type-derived param name.
        JsonArray kept = TriggerSchemaServiceLoader.buildOptionMethods(
                List.of(option("onHubError", "remote", "optional",
                        List.of(param(null, "HubError", "required", null)), ERROR_NIL)),
                "SubscriberService", Set.of("HubError")::contains, "websub");
        Assert.assertEquals(kept.get(0).getAsJsonObject().getAsJsonArray("parameters")
                .get(0).getAsJsonObject().get("name").getAsString(), "hubError");

        // Undeclared bare user types in the returns are equally disqualifying.
        Assert.assertTrue(TriggerSchemaServiceLoader.buildOptionMethods(
                List.of(option("onEvent", "remote", "optional", null,
                        List.of(new TypeRef("Acknowledgement", null)))),
                "Service", NONE, "websub").isEmpty());
    }

    @Test
    public void testGeneratedNameRulesThroughTheLoader() {
        // A bare `Error` slot is named <alias>Error, never the keyword `error`.
        JsonArray errorSlot = TriggerSchemaServiceLoader.buildOptionMethods(
                List.of(option("onError", "remote", "optional",
                        List.of(param(null, "Error", "required", null)), null)),
                "Service", Set.of("Error")::contains, "testmod");
        Assert.assertEquals(errorSlot.get(0).getAsJsonObject().getAsJsonArray("parameters")
                .get(0).getAsJsonObject().get("name").getAsString(), "testmodError");

        // The alias used is the module alias, so a submodule package reduces first.
        JsonArray submodule = TriggerSchemaServiceLoader.buildOptionMethods(
                List.of(option("onError", "remote", "optional",
                        List.of(param(null, "Error", "required", null)), null)),
                "Service", Set.of("Error")::contains, "trigger.github");
        Assert.assertEquals(submodule.get(0).getAsJsonObject().getAsJsonArray("parameters")
                .get(0).getAsJsonObject().get("name").getAsString(), "githubError");

        // A generated name colliding with a sibling's authored name falls back positionally.
        JsonArray collision = TriggerSchemaServiceLoader.buildOptionMethods(
                List.of(option("onTwo", "remote", "optional",
                        List.of(param("event", "Event", "required", null),
                                param(null, "Event", "optional", null)), null)),
                "Service", Set.of("Event")::contains, "testmod");
        JsonArray params = collision.get(0).getAsJsonObject().getAsJsonArray("parameters");
        Assert.assertEquals(params.get(0).getAsJsonObject().get("name").getAsString(), "event");
        Assert.assertEquals(params.get(1).getAsJsonObject().get("name").getAsString(), "param2");
    }

    @Test
    public void testBuildOptionMethodsMarksRepeatableParamsAndDropsNilReturns() {
        JsonArray methods = TriggerSchemaServiceLoader.buildOptionMethods(
                List.of(option("onTool", "remote", "optional",
                        List.of(param("meta", "ToolMeta", "required", null),
                                param(null, "string", "optional", "many")),
                        List.of(new TypeRef("()", null)))),
                "Service", Set.of("ToolMeta")::contains, "testmod");
        JsonObject method = methods.get(0).getAsJsonObject();
        JsonArray parameters = method.getAsJsonArray("parameters");

        // A repeatable slot used to be dropped here, which cost the prompt everything §7 says about it.
        // It now reaches the wire marked, and it is the consumer that keeps it out of the signature.
        Assert.assertEquals(parameters.size(), 2,
                "A repeatable slot is carried to the wire, not discarded");
        Assert.assertEquals(parameters.get(0).getAsJsonObject()
                .getAsJsonObject("type").get("name").getAsString(), "ToolMeta");
        Assert.assertFalse(parameters.get(0).getAsJsonObject().has("repeatable"),
                "Spec §7: \"Absent = at most one\" is the default and is never restated");

        JsonObject repeatable = parameters.get(1).getAsJsonObject();
        Assert.assertTrue(repeatable.get("repeatable").getAsBoolean());
        Assert.assertEquals(repeatable.getAsJsonObject("type").get("name").getAsString(), "string");
        Assert.assertFalse(repeatable.has("name"),
                "§7 leaves each occurrence's name to the author, so none is synthesized");

        Assert.assertFalse(method.has("return"), "A nil return carries no information");
    }

    // ---- two-tier metadata precedence -------------------------------------------------

    private static final TriggerMetadataModel BUNDLED = new TriggerMetadataModel("v1.0",
            List.of(new TriggerMetadataModel.Listener(new TypeRef("Listener", null), null, null, null, null,
                    null, null)),
            List.of(), null, null);

    private static final TriggerMetadataModel SHIPPED = new TriggerMetadataModel("v1.0",
            List.of(new TriggerMetadataModel.Listener(new TypeRef("ShippedListener", null), null, null, null,
                    null, null, null)),
            List.of(), null, null);

    @Test
    public void testAConnectorsOwnDocumentWinsOverTheBundledCopy() {
        TriggerSchemaServiceLoader.MetadataResolution resolved = TriggerSchemaServiceLoader.decideMetadata(
                "ballerinax/kafka", read(SHIPPED, LibraryMetadataReader.MetadataOutcome.USABLE),
                () -> Optional.of(BUNDLED));
        Assert.assertSame(resolved.document().orElseThrow(), SHIPPED,
                "the connector's own document is versioned with the connector, so it is authoritative");
        Assert.assertTrue(resolved.documentPresent());
    }

    @Test
    public void testTheBundledCopyIsUsedOnlyWhenTheConnectorShipsNothing() {
        TriggerSchemaServiceLoader.MetadataResolution resolved = TriggerSchemaServiceLoader.decideMetadata(
                "ballerinax/kafka", read(null, LibraryMetadataReader.MetadataOutcome.ABSENT),
                () -> Optional.of(BUNDLED));
        Assert.assertSame(resolved.document().orElseThrow(), BUNDLED);
        Assert.assertTrue(resolved.documentPresent());
    }

    @Test
    public void testARefusedShippedDocumentIsNotReplacedByTheBundledCopy() {
        // The regression: a v2 document was read as an absence, so the LS served its own bundled v1 copy of
        // the SAME connector — describing a release the package no longer matches, and presenting it as
        // authoritative. That is the "confident-looking downgrade" this loader's fallback policy refuses.
        for (LibraryMetadataReader.MetadataOutcome refused : List.of(
                LibraryMetadataReader.MetadataOutcome.UNSUPPORTED_VERSION,
                LibraryMetadataReader.MetadataOutcome.MALFORMED)) {
            TriggerSchemaServiceLoader.MetadataResolution resolved =
                    TriggerSchemaServiceLoader.decideMetadata("ballerinax/kafka", read(null, refused),
                            () -> Optional.of(BUNDLED));
            Assert.assertTrue(resolved.document().isEmpty(),
                    refused + " must not be served the bundled document");
            // True, so the caller does not silently substitute the SQLite index either: the library renders
            // its curated overlay and logs why, which is findable.
            Assert.assertTrue(resolved.documentPresent(),
                    refused + " is a library WITH metadata that yielded nothing");
        }
    }

    @Test
    public void testTheBundledTierIsNotEvenConsultedForARefusedShippedDocument() {
        // Not merely unused — unread. A supplier that throws proves the bundled tier is never reached, so a
        // future change cannot reintroduce the substitution by using the value later.
        TriggerSchemaServiceLoader.MetadataResolution resolved =
                TriggerSchemaServiceLoader.decideMetadata("ballerinax/kafka",
                        read(null, LibraryMetadataReader.MetadataOutcome.UNSUPPORTED_VERSION),
                        () -> {
                            throw new AssertionError("the bundled document must not be consulted");
                        });
        Assert.assertTrue(resolved.document().isEmpty());
    }

    @Test
    public void testNoDocumentAtEitherTierIsReportedAsNoDocument() {
        // The ordinary case for the overwhelming majority of libraries: the caller MAY use the service index.
        TriggerSchemaServiceLoader.MetadataResolution resolved = TriggerSchemaServiceLoader.decideMetadata(
                "ballerina/log", read(null, LibraryMetadataReader.MetadataOutcome.ABSENT),
                Optional::empty);
        Assert.assertTrue(resolved.document().isEmpty());
        Assert.assertFalse(resolved.documentPresent(),
                "no document was found, so falling back to the index is correct rather than a downgrade");
    }

    private static LibraryMetadataReader.MetadataRead read(TriggerMetadataModel document,
                                                           LibraryMetadataReader.MetadataOutcome outcome) {
        return new LibraryMetadataReader.MetadataRead(document, outcome);
    }
}
