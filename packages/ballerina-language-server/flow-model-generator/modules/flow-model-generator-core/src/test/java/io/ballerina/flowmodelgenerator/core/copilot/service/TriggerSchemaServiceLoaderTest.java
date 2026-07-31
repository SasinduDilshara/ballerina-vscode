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
import io.ballerina.modelgenerator.commons.trigger.models.TriggerMetadataModel.ServiceType.HandlerOption;
import io.ballerina.modelgenerator.commons.trigger.models.TriggerMetadataModel.ServiceType.Param;
import io.ballerina.modelgenerator.commons.trigger.models.TypeRef;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.List;
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

    @Test
    public void testSchemaDrivenSetIsDiscoveredFromTheAvailableDocuments() {
        // Discovered by looking the document up, not from an allow-list. A null package means only the
        // LS-bundled tier is consulted, which is where each of these documents lives today.
        for (String library : List.of("ballerinax/kafka", "ballerinax/rabbitmq", "ballerina/ftp",
                "ballerina/mcp", "ballerinax/trigger.github", "ballerina/smb", "ballerina/websub",
                "ballerinax/trigger.google.calendar")) {
            Assert.assertTrue(TriggerSchemaServiceLoader.isSchemaDriven(library, null),
                    library + " has a bundled document and must be schema-driven");
        }
        // mssql's document is published under a different module name than the package, so it is
        // reached through the alias rather than by the package name.
        Assert.assertTrue(TriggerSchemaServiceLoader.isSchemaDriven("ballerinax/mssql", null));

        // No document bundled -> stays on the SQLite service-index.
        Assert.assertFalse(TriggerSchemaServiceLoader.isSchemaDriven("ballerinax/asb", null));
        Assert.assertFalse(TriggerSchemaServiceLoader.isSchemaDriven("ballerina/http", null));
        Assert.assertFalse(TriggerSchemaServiceLoader.isSchemaDriven("ballerina/graphql", null));
        Assert.assertFalse(TriggerSchemaServiceLoader.isSchemaDriven("ballerinax/no.such.package", null));
    }

    @Test
    public void testLoadServicesMissingInputsYieldEmpty() {
        Assert.assertTrue(TriggerSchemaServiceLoader.loadServices("ballerinax/asb", null, null).isEmpty(),
                "Non-schema-driven library must yield empty");
        Assert.assertTrue(TriggerSchemaServiceLoader.loadServices("ballerinax/kafka", null, null).isEmpty(),
                "Missing package/semantic model must yield empty");
    }

    // ---- buildOptionMethods ----------------------------------------------------------

    private static HandlerOption option(String name, String kind, String presence, List<Param> params,
                                        List<TypeRef> returns) {
        return new HandlerOption(name, kind, presence, null, params, returns, null, null, null, null, null);
    }

    private static Param param(String name, String type, String presence, String addMode) {
        return new Param(name, List.of(new TypeRef(type, null)), presence, addMode, null, null);
    }

    private static final List<TypeRef> ERROR_NIL =
            List.of(new TypeRef("error", null), new TypeRef("()", null));

    @Test
    public void testBuildOptionMethodsSkipsNullAndNamelessOptions() {
        JsonArray methods = TriggerSchemaServiceLoader.buildOptionMethods(
                java.util.Arrays.asList(
                        null,
                        option(null, "remote", "optional", null, ERROR_NIL)),
                "Service", NONE, "testmod", null);
        Assert.assertTrue(methods.isEmpty(), "Null and name-less options must be skipped");
        Assert.assertTrue(TriggerSchemaServiceLoader.buildOptionMethods(
                null, "Service", NONE, "testmod", null).isEmpty());
    }

    /**
     * A wildcard option is the service type's whole handler contract — it must be emitted, with a
     * placeholder name and the flag that says the author picks the real one.
     */
    @Test
    public void testBuildOptionMethodsEmitsWildcardWithPlaceholderName() {
        JsonArray methods = TriggerSchemaServiceLoader.buildOptionMethods(
                List.of(option("*", "remote", "optional", null, ERROR_NIL)),
                "Service", NONE, "testmod", null);
        Assert.assertEquals(methods.size(), 1, "The wildcard handler must be emitted");
        JsonObject method = methods.get(0).getAsJsonObject();
        Assert.assertTrue(method.get("nameIsUserDefined").getAsBoolean());
        // No annotation is bound here, so the neutral placeholder is used.
        Assert.assertEquals(method.get("name").getAsString(), "handlerName");
        Assert.assertNotEquals(method.get("name").getAsString(), "*",
                "The metadata wildcard is never emitted as an identifier");
    }

    /**
     * The placeholder is derived from the document's own annotation id — nothing library-specific.
     */
    @Test
    public void testPlaceholderHandlerNameDerivesFromBoundAnnotation() {
        Assert.assertEquals(TriggerSchemaServiceLoader.placeholderHandlerName(
                new HandlerOption("*", "remote", null, List.of("tool"), null, null,
                        null, null, null, null, null)), "toolName");
        Assert.assertEquals(TriggerSchemaServiceLoader.placeholderHandlerName(
                new HandlerOption("*", "remote", null, List.of("eventSubscription"), null, null,
                        null, null, null, null, null)), "eventSubscriptionName");
        // No binding, an empty binding, and a null list all fall back to the neutral name.
        Assert.assertEquals(TriggerSchemaServiceLoader.placeholderHandlerName(
                new HandlerOption("*", "remote", null, null, null, null,
                        null, null, null, null, null)), "handlerName");
        Assert.assertEquals(TriggerSchemaServiceLoader.placeholderHandlerName(
                new HandlerOption("*", "remote", null, java.util.Arrays.asList((String) null), null, null,
                        null, null, null, null, null)), "handlerName");
        // A derived name that would collide with a keyword is rejected.
        Assert.assertEquals(TriggerSchemaServiceLoader.placeholderHandlerName(
                new HandlerOption("*", "remote", null, List.of("type"), null, null,
                        null, null, null, null, null)), "typeName");
    }

    /**
     * A repeatable slot is emitted once and flagged, rather than dropped: its type and any annotation
     * bound to it are real even though the count is open-ended.
     */
    @Test
    public void testBuildOptionMethodsEmitsRepeatableSlotOnceAndFlagsIt() {
        JsonArray methods = TriggerSchemaServiceLoader.buildOptionMethods(
                List.of(option("onEvent", "remote", "optional",
                        List.of(param("session", "Session", "optional", null),
                                param(null, "anydata", "optional", "many")), ERROR_NIL)),
                "Service", Set.of("Session")::contains, "testmod", null);
        JsonArray params = methods.get(0).getAsJsonObject().getAsJsonArray("parameters");
        Assert.assertEquals(params.size(), 2, "The repeatable slot must be emitted, not skipped");
        Assert.assertFalse(params.get(0).getAsJsonObject().has("repeatable"));
        Assert.assertTrue(params.get(1).getAsJsonObject().get("repeatable").getAsBoolean());
    }

    @Test
    public void testBuildOptionMethodsResourceKindAndNoParams() {
        JsonArray methods = TriggerSchemaServiceLoader.buildOptionMethods(
                List.of(option("chat", "resource", "required", null, ERROR_NIL)),
                "Service", NONE, "testmod", null);
        JsonObject method = methods.get(0).getAsJsonObject();
        Assert.assertEquals(method.get("type").getAsString(), "resource");
        Assert.assertFalse(method.has("parameters"), "No parameters key for a param-less option");
        Assert.assertFalse(method.has("description"), "No description without UI docs");
        Assert.assertFalse(method.has("optional"), "Function-level optional must never be emitted");
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
                "Service", NONE, "testmod", null);
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
                "Service", Set.of("WatchEvent")::contains, "testmod", null);
        JsonObject generatedParam = generated.get(0).getAsJsonObject()
                .getAsJsonArray("parameters").get(0).getAsJsonObject();
        Assert.assertEquals(generatedParam.get("name").getAsString(), "watchEvent");
        Assert.assertFalse(generatedParam.has("optional"));

        // A name-less slot whose type yields no identifier falls back positionally.
        JsonArray synthetic = TriggerSchemaServiceLoader.buildOptionMethods(
                List.of(option("onUnknown", "remote", "optional",
                        List.of(param(null, "json", "required", null)), null)),
                "Service", NONE, "testmod", null);
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
                "SubscriberService", NONE, "websub", null);
        Assert.assertTrue(skipped.isEmpty());

        // Same handler with the type declared: kept, with the type-derived param name.
        JsonArray kept = TriggerSchemaServiceLoader.buildOptionMethods(
                List.of(option("onHubError", "remote", "optional",
                        List.of(param(null, "HubError", "required", null)), ERROR_NIL)),
                "SubscriberService", Set.of("HubError")::contains, "websub", null);
        Assert.assertEquals(kept.get(0).getAsJsonObject().getAsJsonArray("parameters")
                .get(0).getAsJsonObject().get("name").getAsString(), "hubError");

        // Undeclared bare user types in the returns are equally disqualifying.
        Assert.assertTrue(TriggerSchemaServiceLoader.buildOptionMethods(
                List.of(option("onEvent", "remote", "optional", null,
                        List.of(new TypeRef("Acknowledgement", null)))),
                "Service", NONE, "websub", null).isEmpty());
    }

    @Test
    public void testGeneratedNameRulesThroughTheLoader() {
        // A bare `Error` slot is named <alias>Error, never the keyword `error`.
        JsonArray errorSlot = TriggerSchemaServiceLoader.buildOptionMethods(
                List.of(option("onError", "remote", "optional",
                        List.of(param(null, "Error", "required", null)), null)),
                "Service", Set.of("Error")::contains, "testmod", null);
        Assert.assertEquals(errorSlot.get(0).getAsJsonObject().getAsJsonArray("parameters")
                .get(0).getAsJsonObject().get("name").getAsString(), "testmodError");

        // The alias used is the module alias, so a submodule package reduces first.
        JsonArray submodule = TriggerSchemaServiceLoader.buildOptionMethods(
                List.of(option("onError", "remote", "optional",
                        List.of(param(null, "Error", "required", null)), null)),
                "Service", Set.of("Error")::contains, "trigger.github", null);
        Assert.assertEquals(submodule.get(0).getAsJsonObject().getAsJsonArray("parameters")
                .get(0).getAsJsonObject().get("name").getAsString(), "githubError");

        // A generated name colliding with a sibling's authored name falls back positionally.
        JsonArray collision = TriggerSchemaServiceLoader.buildOptionMethods(
                List.of(option("onTwo", "remote", "optional",
                        List.of(param("event", "Event", "required", null),
                                param(null, "Event", "optional", null)), null)),
                "Service", Set.of("Event")::contains, "testmod", null);
        JsonArray params = collision.get(0).getAsJsonObject().getAsJsonArray("parameters");
        Assert.assertEquals(params.get(0).getAsJsonObject().get("name").getAsString(), "event");
        Assert.assertEquals(params.get(1).getAsJsonObject().get("name").getAsString(), "param2");
    }

    @Test
    public void testBuildOptionMethodsFlagsRepeatableParamsAndDropsNilReturns() {
        JsonArray methods = TriggerSchemaServiceLoader.buildOptionMethods(
                List.of(option("onTool", "remote", "optional",
                        List.of(param("meta", "ToolMeta", "required", null),
                                param(null, "string", "optional", "many")),
                        List.of(new TypeRef("()", null)))),
                "Service", Set.of("ToolMeta")::contains, "testmod", null);
        JsonObject method = methods.get(0).getAsJsonObject();
        JsonArray params = method.getAsJsonArray("parameters");
        Assert.assertEquals(params.size(), 2,
                "An addMode: many slot is emitted once and flagged, not skipped");
        Assert.assertEquals(params.get(0).getAsJsonObject()
                .getAsJsonObject("type").get("name").getAsString(), "ToolMeta");
        Assert.assertFalse(params.get(0).getAsJsonObject().has("repeatable"));
        Assert.assertTrue(params.get(1).getAsJsonObject().get("repeatable").getAsBoolean());
        Assert.assertFalse(method.has("return"), "A nil return carries no information");
    }
}
