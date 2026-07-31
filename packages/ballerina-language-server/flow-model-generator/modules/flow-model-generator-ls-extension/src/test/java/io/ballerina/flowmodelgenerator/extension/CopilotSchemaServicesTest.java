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
import io.ballerina.compiler.api.SemanticModel;
import io.ballerina.flowmodelgenerator.core.copilot.service.ServiceLoader;
import io.ballerina.modelgenerator.commons.PackageUtil;
import io.ballerina.projects.Package;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * End-to-end tests for the schema-driven Copilot service loader: trigger metadata (structure) +
 * semantic model (listener params, concrete methods + their doc comments, validation), entered
 * through the public
 * {@link ServiceLoader#loadAllServices(String, Package, SemanticModel)} overload — exactly the call
 * {@code CopilotLibraryManager} makes.
 *
 * <p>Each library resolves its latest cached/central package, so assertions pin only
 * version-stable facts (handler vocabulary, param names, doc presence, type link shapes) rather
 * than full golden documents.
 *
 * @since 1.7.0
 */
public class CopilotSchemaServicesTest {

    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().create();
    private static final Path OUTPUT_DIR = Path.of("build", "services-comparison");

    private final Map<String, JsonArray> cache = new HashMap<>();

    private JsonArray load(String libraryName) {
        return cache.computeIfAbsent(libraryName, lib -> {
            String[] parts = lib.split("/");
            Optional<Package> pkgOpt = PackageUtil.getModulePackage(
                    PackageUtil.getSampleProject(), parts[0], parts[1]);
            if (pkgOpt.isEmpty()) {
                throw new SkipException("Could not resolve package for " + lib);
            }
            Package pkg = pkgOpt.get();
            SemanticModel semanticModel = PackageUtil.getCompilation(pkg)
                    .getSemanticModel(pkg.getDefaultModule().moduleId());
            JsonArray services = ServiceLoader.loadAllServices(lib, pkg, semanticModel);
            dump(lib, services);
            return services;
        });
    }

    private void dump(String libraryName, JsonArray services) {
        try {
            Path dir = OUTPUT_DIR.resolve(libraryName.replace('/', '_').replace('.', '_') + "_schema");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("services.json"), PRETTY.toJson(services));
        } catch (IOException e) {
            // Dumps are for manual review only; never fail the test on IO.
        }
    }

    // ---- helpers -------------------------------------------------------------------

    private static JsonObject serviceNamed(JsonArray services, String name) {
        for (JsonElement element : services) {
            JsonObject svc = element.getAsJsonObject();
            if (svc.has("name") && name.equals(svc.get("name").getAsString())) {
                return svc;
            }
        }
        Assert.fail("No service named " + name + " in " + services);
        return null;
    }

    private static JsonObject methodNamed(JsonObject service, String name) {
        for (JsonElement element : service.getAsJsonArray("methods")) {
            JsonObject method = element.getAsJsonObject();
            if (name.equals(method.get("name").getAsString())) {
                return method;
            }
        }
        Assert.fail("No method named " + name + " in " + service);
        return null;
    }

    private static List<String> methodNames(JsonObject service) {
        List<String> names = new ArrayList<>();
        if (!service.has("methods")) {
            return names;
        }
        service.getAsJsonArray("methods").forEach(m ->
                names.add(m.getAsJsonObject().get("name").getAsString()));
        return names;
    }

    private static JsonObject paramNamed(JsonObject method, String name) {
        for (JsonElement element : method.getAsJsonArray("parameters")) {
            JsonObject param = element.getAsJsonObject();
            if (name.equals(param.get("name").getAsString())) {
                return param;
            }
        }
        Assert.fail("No parameter named " + name + " in " + method);
        return null;
    }

    private static List<String> paramNames(JsonObject method) {
        List<String> names = new ArrayList<>();
        if (!method.has("parameters")) {
            return names;
        }
        method.getAsJsonArray("parameters").forEach(p ->
                names.add(p.getAsJsonObject().get("name").getAsString()));
        return names;
    }

    private static void assertInternalLink(JsonObject typed, String recordName) {
        JsonObject type = typed.getAsJsonObject("type");
        Assert.assertTrue(type.has("links"), "Expected links on type " + type);
        JsonObject link = type.getAsJsonArray("links").get(0).getAsJsonObject();
        Assert.assertEquals(link.get("category").getAsString(), "internal");
        Assert.assertEquals(link.get("recordName").getAsString(), recordName);
    }

    // ---- kafka -----------------------------------------------------------------------

    @Test
    public void testKafkaSchemaServices() {
        JsonArray services = load("ballerinax/kafka");
        Assert.assertEquals(services.size(), 1);

        JsonObject service = serviceNamed(services, "Service");
        Assert.assertEquals(service.get("type").getAsString(), "fixed");

        JsonObject listener = service.getAsJsonObject("listener");
        Assert.assertEquals(listener.get("name").getAsString(), "kafka:Listener");
        List<String> initParams = new ArrayList<>();
        listener.getAsJsonArray("parameters").forEach(p ->
                initParams.add(p.getAsJsonObject().get("name").getAsString()));
        Assert.assertTrue(initParams.contains("bootstrapServers"),
                "Expected bootstrapServers in " + initParams);
        Assert.assertTrue(initParams.contains("config"), "Expected config in " + initParams);
        JsonObject bootstrapServers = null;
        for (JsonElement p : listener.getAsJsonArray("parameters")) {
            if ("bootstrapServers".equals(p.getAsJsonObject().get("name").getAsString())) {
                bootstrapServers = p.getAsJsonObject();
            }
        }
        Assert.assertNotNull(bootstrapServers);
        Assert.assertFalse(bootstrapServers.get("description").getAsString().isEmpty(),
                "Listener param docs must come from the init method's parameterMap");

        Assert.assertEquals(methodNames(service), List.of("onConsumerRecord", "onError"));

        JsonObject onConsumerRecord = methodNamed(service, "onConsumerRecord");
        Assert.assertEquals(onConsumerRecord.get("type").getAsString(), "remote");
        // FLAG: a marker service type declares no methods, so neither the metadata document nor the
        // library carries a handler description — the key is omitted, never fabricated.
        Assert.assertFalse(onConsumerRecord.has("description"),
                "Marker-type handlers have no description source");
        Assert.assertFalse(onConsumerRecord.has("optional"),
                "Function-level optional must never be emitted");

        // The metadata document states no names for these slots (a handler param name is the service
        // author's choice), so they are generated: the AnydataX|BytesX union collapses to one stable
        // name, and the first union member supplies the type.
        Assert.assertEquals(paramNames(onConsumerRecord), List.of("consumerRecords", "caller"));
        JsonObject records = paramNamed(onConsumerRecord, "consumerRecords");
        Assert.assertEquals(records.getAsJsonObject("type").get("name").getAsString(),
                "AnydataConsumerRecord[]");
        assertInternalLink(records, "AnydataConsumerRecord[]");
        JsonObject caller = paramNamed(onConsumerRecord, "caller");
        Assert.assertTrue(caller.get("optional").getAsBoolean(),
                "presence: optional must map to the param optional flag");

        Assert.assertEquals(onConsumerRecord.getAsJsonObject("return")
                .getAsJsonObject("type").get("name").getAsString(), "error?");

        JsonObject onError = methodNamed(service, "onError");
        // A bare `Error` slot is generated as <alias>Error — never the keyword `error`.
        Assert.assertEquals(paramNames(onError), List.of("kafkaError"));
        Assert.assertEquals(paramNamed(onError, "kafkaError").getAsJsonObject("type")
                .get("name").getAsString(), "Error");
    }

    // ---- rabbitmq ----------------------------------------------------------------------

    @Test
    public void testRabbitmqSchemaServices() {
        JsonArray services = load("ballerinax/rabbitmq");
        JsonObject service = serviceNamed(services, "Service");

        Assert.assertEquals(methodNames(service), List.of("onMessage", "onRequest", "onError"));

        JsonObject onRequest = methodNamed(service, "onRequest");
        // Generated names, which here reproduce exactly what the retired service-index carried.
        Assert.assertEquals(paramNames(onRequest), List.of("message", "caller"));
        Assert.assertEquals(onRequest.getAsJsonObject("return")
                .getAsJsonObject("type").get("name").getAsString(), "anydata|error");
        Assert.assertFalse(onRequest.has("description"), "Marker-type handler: no description source");

        JsonObject onError = methodNamed(service, "onError");
        Assert.assertEquals(paramNames(onError), List.of("message", "rabbitmqError"));
    }

    // ---- ftp ---------------------------------------------------------------------------

    @Test
    public void testFtpSchemaServices() {
        JsonArray services = load("ballerina/ftp");
        JsonObject service = serviceNamed(services, "Service");

        // The metadata's handler vocabulary, including onFileChange (absent from the old index).
        Assert.assertEquals(methodNames(service), List.of("onFileCsv", "onFileJson", "onFileXml",
                "onFileText", "onFile", "onFileDelete", "onError", "onFileChange"));

        // Metadata structure wins: onFileJson has no caller; names come from the metadata file.
        JsonObject onFileJson = methodNamed(service, "onFileJson");
        Assert.assertEquals(paramNames(onFileJson), List.of("content", "fileInfo"));
        Assert.assertEquals(paramNamed(onFileJson, "content").getAsJsonObject("type")
                .get("name").getAsString(), "json");
        assertInternalLink(paramNamed(onFileJson, "fileInfo"), "FileInfo");

        // First union member is the codegen default for the CSV content type.
        JsonObject onFileCsv = methodNamed(service, "onFileCsv");
        Assert.assertEquals(paramNamed(onFileCsv, "contents").getAsJsonObject("type")
                .get("name").getAsString(), "string[][]");
        Assert.assertTrue(paramNamed(onFileCsv, "caller").get("optional").getAsBoolean());
    }

    // ---- mssql (metadata keyed as mssql.cdc) ---------------------------------------------

    @Test
    public void testMssqlSchemaServices() {
        JsonArray services = load("ballerinax/mssql");
        JsonObject service = serviceNamed(services, "Service");

        JsonObject listener = service.getAsJsonObject("listener");
        Assert.assertEquals(listener.get("name").getAsString(), "mssql:CdcListener",
                "The metadata-declared CdcListener must validate against the resolved package");

        Assert.assertEquals(methodNames(service),
                List.of("onRead", "onCreate", "onUpdate", "onDelete", "onError"));

        JsonObject onUpdate = methodNamed(service, "onUpdate");
        Assert.assertEquals(paramNames(onUpdate), List.of("beforeEntry", "afterEntry", "tableName"));
        Assert.assertEquals(paramNamed(onUpdate, "beforeEntry").getAsJsonObject("type")
                .get("name").getAsString(), "record {}");

        // Cross-module TypeRef: prefixed with the foreign alias and never linked.
        JsonObject onError = methodNamed(service, "onError");
        JsonObject cdcError = paramNamed(onError, "cdcError");
        Assert.assertEquals(cdcError.getAsJsonObject("type").get("name").getAsString(), "cdc:Error");
        Assert.assertFalse(cdcError.getAsJsonObject("type").has("links"));

        // Metadata declares returns: () — a nil return carries no information and must be omitted.
        Assert.assertFalse(onError.has("return"));
    }

    // ---- mcp ---------------------------------------------------------------------------

    @Test
    public void testMcpSchemaServices() {
        JsonArray services = load("ballerina/mcp");

        List<String> names = new ArrayList<>();
        services.forEach(s -> names.add(s.getAsJsonObject().get("name").getAsString()));
        Assert.assertTrue(names.contains("Service"), "Expected Service in " + names);
        // Only service types the resolved package actually declares are emitted; which ones exist
        // depends on the resolved mcp version, so this asserts the invariant rather than a fixed set.
        for (String name : names) {
            Assert.assertTrue(
                    List.of("Service", "AdvancedService", "StreamableHttpService",
                            "StreamableHttpAdvancedService").contains(name),
                    "Unexpected mcp service type " + name + " in " + names);
        }

        for (JsonElement element : services) {
            JsonObject svc = element.getAsJsonObject();
            String listenerName = svc.getAsJsonObject("listener").get("name").getAsString();
            Assert.assertTrue(
                    List.of("mcp:Listener", "mcp:StreamableHttpListener").contains(listenerName),
                    "The listener must be a class the resolved package declares, got " + listenerName);
            if ("Service".equals(svc.get("name").getAsString())) {
                // mcp:Service is `distinct service object { }`, so its whole handler contract is the
                // document's wildcard entry: one author-named remote function per tool.
                Assert.assertEquals(methodNames(svc), List.of("toolName"),
                        "The wildcard handler must be emitted under its derived placeholder name");
                JsonObject wildcard = methodNamed(svc, "toolName");
                Assert.assertTrue(wildcard.get("nameIsUserDefined").getAsBoolean());
                Assert.assertNotNull(annotationNamed(wildcard, "Tool"),
                        "The Tool binding must ride along with the wildcard handler");
            }
            if ("AdvancedService".equals(svc.get("name").getAsString())) {
                Assert.assertEquals(methodNames(svc), List.of("onListTools", "onCallTool"),
                        "Concrete service types must introspect their declared methods");
            }
        }
    }

    // ---- trigger.github ---------------------------------------------------------------

    @Test
    public void testTriggerGithubSchemaServices() {
        JsonArray services = load("ballerinax/trigger.github");
        Assert.assertEquals(services.size(), 10);

        JsonObject issues = serviceNamed(services, "IssuesService");
        Assert.assertEquals(issues.getAsJsonObject("listener").get("name").getAsString(),
                "github:Listener");
        Assert.assertTrue(methodNames(issues).contains("onOpened"));

        JsonObject onOpened = methodNamed(issues, "onOpened");
        Assert.assertEquals(onOpened.get("type").getAsString(), "remote");
        // FLAG: github's declared handlers carry no doc comments, so no description is available
        // (matching what the retired service-index served for this library).
        Assert.assertFalse(onOpened.has("description"),
                "No doc comment in source means no description is emitted");
        Assert.assertEquals(paramNames(onOpened), List.of("payload"));
        JsonObject payload = paramNamed(onOpened, "payload");
        Assert.assertEquals(payload.getAsJsonObject("type").get("name").getAsString(), "IssuesEvent");
        assertInternalLink(payload, "IssuesEvent");
        Assert.assertEquals(onOpened.getAsJsonObject("return")
                .getAsJsonObject("type").get("name").getAsString(), "error?");
    }

    @Test
    public void testMcpServiceModelRoundTrip() {
        // CopilotLibraryManager Gson-round-trips every service through the Service model class, so
        // every field the loader emits — including the annotation bindings, the wildcard flag and the
        // repeatable flag — has to survive that trip.
        JsonArray services = load("ballerina/mcp");
        Gson gson = new Gson();
        for (JsonElement element : services) {
            io.ballerina.flowmodelgenerator.core.copilot.model.Service service =
                    gson.fromJson(element, io.ballerina.flowmodelgenerator.core.copilot.model.Service.class);
            JsonObject reSerialized = gson.toJsonTree(service).getAsJsonObject();
            if ("Service".equals(service.getName())) {
                Assert.assertEquals(service.getMethods().size(), 1,
                        "The wildcard handler must survive the round trip");
                io.ballerina.flowmodelgenerator.core.copilot.model.ServiceRemoteFunction wildcard =
                        service.getMethods().get(0);
                Assert.assertEquals(wildcard.getName(), "toolName");
                Assert.assertTrue(wildcard.isNameIsUserDefined());
                Assert.assertEquals(wildcard.getAnnotations().get(0).getName(), "Tool");
                Assert.assertTrue(reSerialized.getAsJsonArray("methods").get(0).getAsJsonObject()
                        .has("nameIsUserDefined"));
                Assert.assertNotNull(service.getAnnotations(),
                        "The service-level ServiceConfig binding must survive the round trip");
                Assert.assertEquals(service.getAnnotations().get(0).getName(), "ServiceConfig");
            }
            if ("AdvancedService".equals(service.getName())) {
                Assert.assertEquals(service.getMethods().size(), 2);
                Assert.assertTrue(reSerialized.has("methods"));
            }
            Assert.assertTrue(List.of("mcp:Listener", "mcp:StreamableHttpListener").contains(
                    reSerialized.getAsJsonObject("listener").get("name").getAsString()));
        }
    }

    // ---- net-new libraries (never in the SQLite index) -----------------------------------

    @Test
    public void testSmbSchemaServices() {
        JsonArray services = load("ballerina/smb");
        JsonObject service = serviceNamed(services, "Service");

        JsonObject listener = service.getAsJsonObject("listener");
        Assert.assertEquals(listener.get("name").getAsString(), "smb:Listener");
        Assert.assertTrue(listener.getAsJsonArray("parameters").size() > 0,
                "Listener init params must be introspected");

        Assert.assertEquals(methodNames(service), List.of("onFileChange", "onFileText", "onFileJson",
                "onFileXml", "onFileCsv", "onFile"));

        JsonObject onFileJson = methodNamed(service, "onFileJson");
        Assert.assertEquals(paramNames(onFileJson), List.of("content", "caller", "fileInfo"));
        Assert.assertTrue(paramNamed(onFileJson, "caller").get("optional").getAsBoolean());
        assertInternalLink(paramNamed(onFileJson, "fileInfo"), "FileInfo");
        // FLAG: no description source exists for smb's marker handlers — no fabricated text.
        Assert.assertFalse(onFileJson.has("description"));
    }

    @Test
    public void testWebsubSchemaServices() {
        JsonArray services = load("ballerina/websub");
        JsonObject service = serviceNamed(services, "SubscriberService");

        Assert.assertEquals(service.getAsJsonObject("listener").get("name").getAsString(),
                "websub:Listener");
        // onHubError is skipped: the metadata declares param type "HubError", which websub 2.15.0
        // does not declare (the compiler plugin expects InternalHubError) — emitting it would
        // render an uncompilable prompt. Reported upstream; it reappears once the metadata is fixed.
        Assert.assertEquals(methodNames(service), List.of("onEventNotification",
                "onSubscriptionVerification", "onUnsubscriptionVerification",
                "onSubscriptionValidationDenied"));

        // The metadata deliberately leaves these params unnamed: names are generated from the
        // declared type — idiomatic, compilable Ballerina.
        JsonObject onEventNotification = methodNamed(service, "onEventNotification");
        Assert.assertEquals(paramNames(onEventNotification), List.of("contentDistributionMessage"));
        assertInternalLink(paramNamed(onEventNotification, "contentDistributionMessage"),
                "ContentDistributionMessage");

        JsonObject onSubscriptionVerification = methodNamed(service, "onSubscriptionVerification");
        Assert.assertEquals(onSubscriptionVerification.getAsJsonObject("return")
                        .getAsJsonObject("type").get("name").getAsString(),
                "SubscriptionVerificationSuccess|SubscriptionVerificationError");
    }

    @Test
    public void testGoogleCalendarSchemaServices() {
        JsonArray services = load("ballerinax/trigger.google.calendar");
        JsonObject service = serviceNamed(services, "CalendarService");

        Assert.assertEquals(service.getAsJsonObject("listener").get("name").getAsString(),
                "calendar:Listener");
        Assert.assertEquals(methodNames(service),
                List.of("onNewEvent", "onEventUpdate", "onEventDelete"));

        JsonObject onNewEvent = methodNamed(service, "onNewEvent");
        Assert.assertEquals(onNewEvent.get("type").getAsString(), "remote");
        Assert.assertEquals(paramNames(onNewEvent), List.of("payload"));
        assertInternalLink(paramNamed(onNewEvent, "payload"), "Event");
        Assert.assertEquals(onNewEvent.getAsJsonObject("return")
                .getAsJsonObject("type").get("name").getAsString(), "error?");
    }

    // The annotation DECLARATION catalog is no longer produced here: it comes from the Semantic
    // Model for every library and every attachment point (see CopilotAnnotationTest). What this
    // loader owns is the metadata document's annotation BINDINGS, asserted in
    // testMetadataAnnotationBindings* below.

    // ---- annotation BINDINGS (the metadata document's exclusive contribution) ----------------

    /**
     * A service-point binding reaches the service object, carries a body generated from the
     * annotation's constraint record, and is marked when the document says it is mandatory.
     * {@code ftp}'s {@code ServiceConfig} is linked by {@code appliesTo} and is {@code required}.
     */
    @Test
    public void testMetadataAnnotationBindingsOnService() {
        JsonObject service = serviceNamed(load("ballerina/ftp"), "Service");
        JsonObject serviceConfig = annotationNamed(service, "ServiceConfig");
        Assert.assertTrue(serviceConfig.get("required").getAsBoolean(),
                "ftp's ServiceConfig is presence: required in the document");
        // A binding is a template for user code, so it is module-qualified just like the service type
        // and listener beside it — the user writes @ftp:ServiceConfig, never a bare @ServiceConfig.
        Assert.assertEquals(serviceConfig.get("module").getAsString(), "ballerina/ftp");
        Assert.assertTrue(serviceConfig.has("value"),
                "The body must be generated from the constraint record, never omitted or guessed");
        Assert.assertTrue(serviceConfig.get("value").getAsString().startsWith("{"),
                "A generated body is a mapping constructor: " + serviceConfig);
    }

    /**
     * {@code smb} links its {@code ServiceConfig} through a {@code rules} member rather than
     * {@code appliesTo} — the other linkage the document uses — and marks both its annotations
     * required.
     */
    @Test
    public void testMetadataAnnotationBindingsLinkedThroughRules() {
        JsonObject service = serviceNamed(load("ballerina/smb"), "Service");
        JsonObject serviceConfig = annotationNamed(service, "ServiceConfig");

        // smb reaches ServiceConfig through a `oneOf` rule ("the path comes from serviceConfig.path OR
        // the service identifier"), so despite the registry's presence: required the annotation itself
        // is one alternative, not a mandate — and smb's SmbServiceConfig has no required field either.
        Assert.assertFalse(serviceConfig.has("required"),
                "A oneOf alternative must not be presented as mandatory: " + serviceConfig);
        // The field that rule names is what the author is expected to supply, so it is in the body even
        // though the record marks it optional.
        Assert.assertEquals(serviceConfig.get("value").getAsString(), "{path: \"\"}",
                "The rule-named field must appear in the generated body");

        // FunctionConfig is required outright — no rule offers an alternative to it.
        JsonObject handler = methodNamed(service, "onFileChange");
        Assert.assertTrue(annotationNamed(handler, "FunctionConfig").get("required").getAsBoolean());
    }

    /** Every ftp handler carries the document's {@code functionConfig} binding. */
    @Test
    public void testMetadataAnnotationBindingsOnEveryHandler() {
        JsonObject service = serviceNamed(load("ballerina/ftp"), "Service");
        for (String handlerName : methodNames(service)) {
            JsonObject handler = methodNamed(service, handlerName);
            Assert.assertNotNull(annotationNamed(handler, "FunctionConfig"),
                    "ftp handler " + handlerName + " must carry the FunctionConfig binding");
        }
    }

    /**
     * A parameter-point binding lands on the parameter it is bound to, and only that one.
     * {@code rabbitmq} binds {@code Payload} to the message parameter of onMessage/onRequest.
     */
    @Test
    public void testMetadataAnnotationBindingsOnHandlerParameter() {
        JsonObject service = serviceNamed(load("ballerinax/rabbitmq"), "Service");
        JsonObject onMessage = methodNamed(service, "onMessage");
        JsonArray params = onMessage.getAsJsonArray("parameters");
        Assert.assertTrue(params.size() > 0);
        Assert.assertNotNull(annotationNamed(params.get(0).getAsJsonObject(), "Payload"),
                "rabbitmq binds Payload to the first parameter of onMessage: " + onMessage);
        Assert.assertFalse(params.get(0).getAsJsonObject().getAsJsonArray("annotations")
                .get(0).getAsJsonObject().has("required"),
                "rabbitmq's Payload is presence: optional, so it must not be marked required");
    }

    /**
     * The cross-module case: {@code mcp} binds {@code ballerina/http}'s {@code Header} to a handler
     * parameter. Neither the binding nor the annotation exists in {@code mcp}'s own module symbols,
     * so this can only come from the document plus a resolution of the declaring package.
     */
    @Test
    public void testMetadataAnnotationBindingsCrossModule() {
        JsonArray services = load("ballerina/mcp");
        JsonObject header = findAnnotationAnywhere(services, "Header");
        if (header == null) {
            throw new SkipException("mcp's Header binding is absent from the resolved metadata/package");
        }
        Assert.assertEquals(header.get("module").getAsString(), "ballerina/http",
                "A cross-module binding must carry its declaring module so it renders as @http:Header");
    }

    /**
     * A wildcard handler is emitted with a placeholder name and the flag that says the author picks
     * the real one, so the annotations bound to it are not lost. {@code mcp}'s marker service types
     * declare no methods at all, so this is their entire handler contract.
     */
    @Test
    public void testMetadataWildcardHandlerIsEmittedWithBindings() {
        JsonArray services = load("ballerina/mcp");
        JsonObject wildcardHandler = null;
        for (JsonElement element : services) {
            JsonObject service = element.getAsJsonObject();
            if (!service.has("methods")) {
                continue;
            }
            for (JsonElement methodElement : service.getAsJsonArray("methods")) {
                JsonObject method = methodElement.getAsJsonObject();
                if (method.has("nameIsUserDefined")) {
                    wildcardHandler = method;
                    break;
                }
            }
        }
        if (wildcardHandler == null) {
            throw new SkipException("No wildcard handler in the resolved mcp metadata");
        }
        Assert.assertTrue(wildcardHandler.get("nameIsUserDefined").getAsBoolean());
        Assert.assertNotEquals(wildcardHandler.get("name").getAsString(), "*",
                "The wildcard must never be emitted as an identifier");
        Assert.assertEquals(wildcardHandler.get("name").getAsString(), "toolName",
                "The placeholder is derived from the bound annotation id (`tool`)");
        Assert.assertNotNull(annotationNamed(wildcardHandler, "Tool"),
                "The wildcard handler must carry the Tool binding: " + wildcardHandler);
    }

    /**
     * A repeatable slot is emitted once and flagged, so a binding attached to it survives while the
     * open-ended count is still communicated.
     */
    @Test
    public void testMetadataRepeatableParamSlotIsFlagged() {
        JsonArray services = load("ballerina/mcp");
        boolean sawRepeatable = false;
        for (JsonElement element : services) {
            JsonObject service = element.getAsJsonObject();
            if (!service.has("methods")) {
                continue;
            }
            for (JsonElement methodElement : service.getAsJsonArray("methods")) {
                JsonObject method = methodElement.getAsJsonObject();
                if (!method.has("parameters")) {
                    continue;
                }
                for (JsonElement paramElement : method.getAsJsonArray("parameters")) {
                    if (paramElement.getAsJsonObject().has("repeatable")) {
                        sawRepeatable = true;
                    }
                }
            }
        }
        Assert.assertTrue(sawRepeatable, "mcp declares addMode: many slots that must be emitted");
    }

    /**
     * An annotation the document does not link to a given service type must not leak onto it.
     * {@code mcp} restricts {@code ServiceConfig} and {@code StreamableHttpServiceConfig} to
     * disjoint {@code appliesTo} sets — the distinction no Semantic Model can make, since both are
     * declared plain {@code on service}.
     */
    @Test
    public void testMetadataAppliesToKeepsServiceBindingsDisjoint() {
        JsonArray services = load("ballerina/mcp");
        JsonObject basic = serviceNamed(services, "Service");
        Assert.assertNotNull(annotationNamed(basic, "ServiceConfig"));
        Assert.assertNull(findAnnotation(basic, "StreamableHttpServiceConfig"),
                "StreamableHttpServiceConfig applies only to the streamable service types");

        JsonObject streamable = findService(services, "StreamableHttpService");
        if (streamable != null) {
            Assert.assertNotNull(annotationNamed(streamable, "StreamableHttpServiceConfig"));
            Assert.assertNull(findAnnotation(streamable, "ServiceConfig"),
                    "ServiceConfig applies only to the non-streamable service types");
        }
    }

    /** A library whose document declares no annotations must gain no bindings at all. */
    @Test
    public void testLibraryWithoutMetadataAnnotationsGainsNoBindings() {
        for (JsonElement element : load("ballerinax/kafka")) {
            JsonObject service = element.getAsJsonObject();
            Assert.assertFalse(service.has("annotations"),
                    "kafka's document declares no annotations: " + service);
            if (!service.has("methods")) {
                continue;
            }
            for (JsonElement methodElement : service.getAsJsonArray("methods")) {
                Assert.assertFalse(methodElement.getAsJsonObject().has("annotations"));
            }
        }
    }

    private static JsonObject annotationNamed(JsonObject owner, String name) {
        JsonObject found = findAnnotation(owner, name);
        Assert.assertNotNull(found, "No annotation named " + name + " on " + owner);
        return found;
    }

    private static JsonObject findAnnotation(JsonObject owner, String name) {
        if (!owner.has("annotations")) {
            return null;
        }
        for (JsonElement element : owner.getAsJsonArray("annotations")) {
            JsonObject annotation = element.getAsJsonObject();
            if (name.equals(annotation.get("name").getAsString())) {
                return annotation;
            }
        }
        return null;
    }

    private static JsonObject findService(JsonArray services, String name) {
        for (JsonElement element : services) {
            JsonObject service = element.getAsJsonObject();
            if (service.has("name") && name.equals(service.get("name").getAsString())) {
                return service;
            }
        }
        return null;
    }

    /** Searches services, their handlers and their parameters for a named binding. */
    private static JsonObject findAnnotationAnywhere(JsonArray services, String name) {
        for (JsonElement element : services) {
            JsonObject service = element.getAsJsonObject();
            JsonObject onService = findAnnotation(service, name);
            if (onService != null) {
                return onService;
            }
            if (!service.has("methods")) {
                continue;
            }
            for (JsonElement methodElement : service.getAsJsonArray("methods")) {
                JsonObject method = methodElement.getAsJsonObject();
                JsonObject onMethod = findAnnotation(method, name);
                if (onMethod != null) {
                    return onMethod;
                }
                if (!method.has("parameters")) {
                    continue;
                }
                for (JsonElement paramElement : method.getAsJsonArray("parameters")) {
                    JsonObject onParam = findAnnotation(paramElement.getAsJsonObject(), name);
                    if (onParam != null) {
                        return onParam;
                    }
                }
            }
        }
        return null;
    }

    // ---- fallback & pinning --------------------------------------------------------------

    @Test
    public void testNonSchemaDrivenLibraryStaysOnServiceIndex() {
        // asb is not schema-driven: the overload must produce exactly the SQLite-path output.
        String library = "ballerinax/asb";
        JsonArray viaOverload = load(library);
        JsonArray viaIndex = ServiceLoader.loadAllServices(library);
        Assert.assertEquals(viaOverload, viaIndex);
    }

    @Test
    public void testTriggerSourcePropertyPinsToIndex() {
        System.setProperty("ballerina.copilot.triggerSource", "index");
        try {
            String library = "ballerinax/kafka";
            // Bypass the cache: this assertion needs the pinned-property behavior.
            String[] parts = library.split("/");
            Optional<Package> pkgOpt = PackageUtil.getModulePackage(
                    PackageUtil.getSampleProject(), parts[0], parts[1]);
            if (pkgOpt.isEmpty()) {
                throw new SkipException("Could not resolve package for " + library);
            }
            Package pkg = pkgOpt.get();
            SemanticModel semanticModel = PackageUtil.getCompilation(pkg)
                    .getSemanticModel(pkg.getDefaultModule().moduleId());
            JsonArray pinned = ServiceLoader.loadAllServices(library, pkg, semanticModel);
            JsonArray viaIndex = ServiceLoader.loadAllServices(library);
            Assert.assertEquals(pinned, viaIndex,
                    "triggerSource=index must force the SQLite path even for schema-driven libraries");
        } finally {
            System.clearProperty("ballerina.copilot.triggerSource");
        }
    }
}
