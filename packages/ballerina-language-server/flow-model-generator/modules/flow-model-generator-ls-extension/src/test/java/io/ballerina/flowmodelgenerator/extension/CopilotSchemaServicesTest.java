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
import io.ballerina.compiler.api.symbols.ClassSymbol;
import io.ballerina.compiler.api.symbols.Symbol;
import io.ballerina.compiler.api.symbols.TypeDefinitionSymbol;
import io.ballerina.flowmodelgenerator.core.copilot.CopilotLibraryManager;
import io.ballerina.flowmodelgenerator.core.copilot.model.Annotation;
import io.ballerina.flowmodelgenerator.core.copilot.model.Library;
import io.ballerina.flowmodelgenerator.core.copilot.model.Type;
import io.ballerina.flowmodelgenerator.core.copilot.model.TypeLink;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
    private static final String MCP = "ballerina/mcp";
    private static final String MCP_PREFIX = "mcp:";
    // The last mcp release before StreamableHttpService / StreamableHttpAdvancedService /
    // StreamableHttpListener existed. Pinned so the loader's skip + listener-fallback guards stay
    // covered once Central catches up with the metadata (see the pinned test below).
    private static final String MCP_PRE_STREAMABLE_HTTP_VERSION = "1.1.0";

    /**
     * The package version each library's assertions were written against.
     *
     * <p>Resolution is otherwise "whatever Central serves latest", which makes every assertion in this
     * class hostage to an upstream release. That is not hypothetical: {@code ballerina/smb} 2.0.1
     * (published 2026-08-06) removed the {@code WatchEvent} record and added a compiler plugin that
     * rejects {@code onFileChange}, and two tests here began failing with {@code expected [6] but found
     * [5]} — a message that says nothing at all about the cause.
     *
     * <p>Pinning moves that failure to exactly one place. {@link #testPinnedVersionsStillMatchLatest}
     * names the library and both versions, so the response is a decision — re-verify the document against
     * the new release, then move the pin — rather than an investigation.
     *
     * <p>A library absent from this map resolves latest, which is right where the assertions do not
     * depend on a release: {@code ballerinax/asb} only asserts that two code paths agree with each other
     * within a single run.
     */
    private static final Map<String, String> PINNED_VERSIONS = Map.ofEntries(
            Map.entry("ballerina/ftp", "2.20.1"),
            Map.entry("ballerina/graphql", "1.17.0"),
            Map.entry("ballerina/http", "2.16.6"),
            Map.entry(MCP, "1.2.0"),
            Map.entry("ballerina/smb", "2.0.1"),
            Map.entry("ballerina/websub", "2.15.0"),
            Map.entry("ballerinax/kafka", "4.6.5"),
            Map.entry("ballerinax/mssql", "1.19.0"),
            Map.entry("ballerinax/rabbitmq", "3.6.0"),
            Map.entry("ballerinax/trigger.github", "0.11.0"),
            Map.entry("ballerinax/trigger.google.calendar", "0.12.0"));

    private final Map<String, JsonArray> cache = new HashMap<>();
    private final Map<String, SemanticModel> semanticModels = new HashMap<>();

    private JsonArray load(String libraryName) {
        return cache.computeIfAbsent(libraryName, lib -> {
            String[] parts = lib.split("/");
            // The pinned version when this library has one, so an upstream release cannot silently
            // rewrite what these assertions are testing against. See PINNED_VERSIONS.
            String pinned = PINNED_VERSIONS.get(lib);
            Optional<Package> pkgOpt = pinned == null
                    ? PackageUtil.getModulePackage(PackageUtil.getSampleProject(), parts[0], parts[1])
                    : PackageUtil.getModulePackage(PackageUtil.getSampleProject(), parts[0], parts[1],
                            pinned);
            if (pkgOpt.isEmpty()) {
                throw new SkipException("Could not resolve package for " + lib
                        + (pinned == null ? "" : ":" + pinned));
            }
            Package pkg = pkgOpt.get();
            SemanticModel semanticModel = PackageUtil.getCompilation(pkg)
                    .getSemanticModel(pkg.getDefaultModule().moduleId());
            semanticModels.put(lib, semanticModel);
            JsonArray services = ServiceLoader.loadAllServices(lib, pkg, semanticModel);
            dump(lib, services);
            return services;
        });
    }

    /**
     * Loads services against one explicitly pinned package version instead of whatever Central
     * currently serves, so a test can assert behaviour that depends on which symbols that version
     * ships.
     */
    private JsonArray loadPinned(String libraryName, String version) {
        String[] parts = libraryName.split("/");
        Optional<Package> pkgOpt = PackageUtil.getModulePackage(
                PackageUtil.getSampleProject(), parts[0], parts[1], version);
        if (pkgOpt.isEmpty()) {
            throw new SkipException("Could not resolve " + libraryName + ":" + version);
        }
        Package pkg = pkgOpt.get();
        SemanticModel semanticModel = PackageUtil.getCompilation(pkg)
                .getSemanticModel(pkg.getDefaultModule().moduleId());
        JsonArray services = ServiceLoader.loadAllServices(libraryName, pkg, semanticModel);
        dump(libraryName + "_" + version, services);
        return services;
    }

    /**
     * Names of the module-level type definitions and classes the resolved package version declares.
     * Every emitted service type and listener must come from this vocabulary, whichever version
     * Central happens to serve — that is the loader's contract, stated without pinning a release.
     */
    private Set<String> declaredNames(String libraryName) {
        load(libraryName);
        SemanticModel semanticModel = semanticModels.get(libraryName);
        Assert.assertNotNull(semanticModel, "No semantic model cached for " + libraryName);
        Set<String> names = new HashSet<>();
        for (Symbol symbol : semanticModel.moduleSymbols()) {
            if (symbol instanceof TypeDefinitionSymbol || symbol instanceof ClassSymbol) {
                symbol.getName().ifPresent(names::add);
            }
        }
        return names;
    }

    private void dump(String label, JsonArray services) {
        try {
            Path dir = OUTPUT_DIR.resolve(label.replaceAll("[^A-Za-z0-9]", "_") + "_schema");
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
        // Spec §5.1 reversed this. A marker service type still declares no method, so there is no doc
        // comment to introspect — which is exactly why the document now AUTHORS one, and why it is the only
        // description a generator will ever see for such a handler.
        Assert.assertTrue(onConsumerRecord.has("description"),
                "spec §5.1 makes `doc` the authored description of a marker-type handler");
        Assert.assertTrue(onConsumerRecord.get("description").getAsString()
                        .startsWith("Invoked with each batch of records polled"),
                onConsumerRecord.toString());
        // Spec §5 scopes `presence` to `addMode: "subset"`, which is kafka's mode — so the document's
        // `presence: "required"` must reach the wire. It used to be dropped, which left a mandatory handler
        // indistinguishable from a skippable one; `onError` below is the optional counterpart.
        Assert.assertTrue(onConsumerRecord.has("optional"),
                "A subset option's presence must be stated, not dropped");
        Assert.assertFalse(onConsumerRecord.get("optional").getAsBoolean(),
                "kafka's onConsumerRecord declares presence: \"required\"");

        // The metadata document states no names for these slots (a handler param name is the service
        // author's choice), so they are generated: the AnydataX|BytesX union collapses to one stable
        // name, and the first union member supplies the type.
        Assert.assertEquals(paramNames(onConsumerRecord), List.of("records", "caller"));
        JsonObject records = paramNamed(onConsumerRecord, "records");
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
        Assert.assertEquals(paramNames(onError), List.of("err"));
        Assert.assertEquals(paramNamed(onError, "err").getAsJsonObject("type")
                .get("name").getAsString(), "Error");
        // The optional counterpart of onConsumerRecord above: both states are expressible, which is the
        // whole point of stating presence at all.
        Assert.assertTrue(onError.get("optional").getAsBoolean(),
                "kafka's onError declares presence: \"optional\"");
    }

    // ---- rabbitmq ----------------------------------------------------------------------

    @Test
    public void testRabbitmqSchemaServices() {
        JsonArray services = load("ballerinax/rabbitmq");
        JsonObject service = serviceNamed(services, "Service");

        Assert.assertEquals(methodNames(service), List.of("onMessage", "onRequest", "onError"));

        JsonObject onRequest = methodNamed(service, "onRequest");
        // Authored names. Spec §7 makes `params[].name` required on every fixed slot, so these come
        // from the document rather than from the generator -- which is why they no longer have to
        // coincide with what the retired service-index happened to carry.
        Assert.assertEquals(paramNames(onRequest), List.of("message", "caller"));
        Assert.assertEquals(onRequest.getAsJsonObject("return")
                .getAsJsonObject("type").get("name").getAsString(), "anydata|error");
        Assert.assertTrue(onRequest.has("description"),
                "spec §5.1 makes `doc` the authored description of a marker-type handler");

        JsonObject onError = methodNamed(service, "onError");
        Assert.assertEquals(paramNames(onError), List.of("message", "err"));
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

        // Spec §1: the service type belongs to ballerinax/cdc, not to the home module, so it is
        // written with its own module's alias — `service cdc:Service on new mssql:CdcListener(...)`.
        // `mssql:Service` would not compile.
        Assert.assertEquals(service.get("serviceTypeModule").getAsString(), "ballerinax/cdc");

        // Spec §2: the listener's side-effect import travels with the service that uses it. The
        // foreign service type itself is NOT imported - provenance is carried by the renderer's
        // Special Agent Note, the same mechanism every other cross-module reference uses.
        List<String> imports = new ArrayList<>();
        for (JsonElement element : service.getAsJsonArray("requiredImports")) {
            JsonObject entry = element.getAsJsonObject();
            imports.add(entry.get("module").getAsString()
                    + (entry.has("alias") ? " as " + entry.get("alias").getAsString() : ""));
        }
        Assert.assertTrue(imports.contains("ballerinax/mssql.cdc.driver as _"),
                "Spec §2 side-effect import missing, got " + imports);
        Assert.assertFalse(imports.contains("ballerinax/cdc"),
                "A foreign service type must not be imported, got " + imports);

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

    @Test
    public void testMssqlCrossModuleServiceAnnotationCarriesItsConstraint() {
        // Spec §8 across a module boundary — the case the whole annotation phase exists for. mssql's
        // document declares a REQUIRED annotation that belongs to ballerinax/cdc, and generated CDC code
        // without it does not work.
        JsonObject service = serviceNamed(load("ballerinax/mssql"), "Service");

        Assert.assertTrue(service.has("annotations"), "the required annotation must reach the catalog");
        JsonArray annotations = service.getAsJsonArray("annotations");
        Assert.assertEquals(annotations.size(), 1);
        JsonObject annotation = annotations.get(0).getAsJsonObject();

        Assert.assertEquals(annotation.get("name").getAsString(), "ServiceConfig");
        Assert.assertEquals(annotation.get("presence").getAsString(), "required");
        Assert.assertEquals(annotation.get("attachPoint").getAsString(), "service");
        // Spec §1: it belongs to another module, so it states that module and renders `@cdc:ServiceConfig`.
        Assert.assertEquals(annotation.get("module").getAsString(), "ballerinax/cdc");

        // The document names the annotation TAG (`ServiceConfig`); its constraining record is called
        // something else entirely (`CdcServiceConfig`) and is declared in a different package. It is
        // introspected from the compiler — the foreign module's symbols are already in this compilation,
        // because a module whose annotation the generated code must attach is necessarily a dependency.
        JsonObject constraint = annotation.getAsJsonObject("typeConstraint");
        Assert.assertNotNull(constraint, "a required annotation with no field source is unusable");
        Assert.assertEquals(constraint.get("name").getAsString(), "CdcServiceConfig");

        // An EXTERNAL link is what carries the record's definition into the prompt, via the same
        // reachability mechanism every other cross-package type reference already uses.
        JsonObject link = constraint.getAsJsonArray("links").get(0).getAsJsonObject();
        Assert.assertEquals(link.get("category").getAsString(), "external");
        Assert.assertEquals(link.get("recordName").getAsString(), "CdcServiceConfig");
        Assert.assertEquals(link.get("libraryName").getAsString(), "ballerinax/cdc");
    }

    @Test
    public void testHomeModuleServiceAnnotationConstraintIsIntrospectedNotGuessed() {
        // ballerina/ftp's document says `type: {"name": "ServiceConfig"}` while the package declares
        // `public annotation ServiceConfiguration ServiceConfig on service;`. Reading the document's name
        // as a type name would emit an attachment constrained by a record that does not exist.
        JsonObject annotation = serviceNamed(load("ballerina/ftp"), "Service")
                .getAsJsonArray("annotations").get(0).getAsJsonObject();

        Assert.assertEquals(annotation.get("name").getAsString(), "ServiceConfig", "the tag");
        Assert.assertEquals(annotation.get("presence").getAsString(), "required");
        Assert.assertFalse(annotation.has("module"), "a home-module annotation states no module");
        Assert.assertEquals(annotation.getAsJsonObject("typeConstraint").get("name").getAsString(),
                "ServiceConfiguration", "the constraint differs from the tag and comes from the compiler");
        Assert.assertEquals(annotation.getAsJsonObject("typeConstraint").getAsJsonArray("links")
                .get(0).getAsJsonObject().get("category").getAsString(), "internal");
    }

    // ---- mcp ---------------------------------------------------------------------------

    @Test
    public void testMcpSchemaServices() {
        JsonArray services = load(MCP);
        Set<String> declared = declaredNames(MCP);

        List<String> names = new ArrayList<>();
        services.forEach(s -> names.add(s.getAsJsonObject().get("name").getAsString()));

        // Present in every mcp release the metadata targets.
        Assert.assertTrue(names.contains("Service"), "Expected Service in " + names);
        Assert.assertTrue(names.contains("AdvancedService"), "Expected AdvancedService in " + names);

        // The validation guard as an invariant rather than as one release's snapshot: the metadata
        // may name service types and a listener authored ahead of a release, and anything the
        // resolved version does not declare must never reach the prompt. Which types a given mcp
        // ships is deliberately not asserted here — see the pinned test for that.
        for (String name : names) {
            Assert.assertTrue(declared.contains(name),
                    "Emitted service type " + name + " is not declared by the resolved package: " + declared);
        }

        for (JsonElement element : services) {
            JsonObject svc = element.getAsJsonObject();
            assertListenerIsDeclared(svc.getAsJsonObject("listener").get("name").getAsString(), declared);
            if ("Service".equals(svc.get("name").getAsString())) {
                Assert.assertFalse(svc.has("methods"),
                        "Wildcard (addMode: many) handlers must not surface as literal methods");
            }
            if ("AdvancedService".equals(svc.get("name").getAsString())) {
                Assert.assertEquals(methodNames(svc), List.of("onListTools", "onCallTool"),
                        "Concrete service types must introspect their declared methods");
            }
        }
    }

    /**
     * A listener must be module-qualified and name a class the resolved package actually declares —
     * otherwise the generated {@code on new mcp:X(...)} would not compile.
     */
    private static void assertListenerIsDeclared(String listenerName, Set<String> declared) {
        Assert.assertTrue(listenerName.startsWith(MCP_PREFIX),
                "Listener must be module-qualified, got: " + listenerName);
        Assert.assertTrue(declared.contains(listenerName.substring(MCP_PREFIX.length())),
                "Listener " + listenerName + " is not a class the resolved package declares: " + declared);
    }

    /**
     * Pins the last mcp release before {@code StreamableHttpService},
     * {@code StreamableHttpAdvancedService} and {@code StreamableHttpListener} existed, so the two
     * guards the schema loader exists for stay exercised deterministically rather than only while
     * Central lags the metadata:
     * <ul>
     *   <li>a metadata-declared service type the resolved version does not ship is skipped;</li>
     *   <li>a metadata-declared listener it does not ship falls back to the conventional class.</li>
     * </ul>
     */
    @Test
    public void testMcpSchemaServicesSkipsSymbolsAbsentFromPinnedVersion() {
        JsonArray services = loadPinned(MCP, MCP_PRE_STREAMABLE_HTTP_VERSION);

        List<String> names = new ArrayList<>();
        services.forEach(s -> names.add(s.getAsJsonObject().get("name").getAsString()));

        Assert.assertTrue(names.contains("Service"), "Expected Service in " + names);
        Assert.assertTrue(names.contains("AdvancedService"), "Expected AdvancedService in " + names);
        Assert.assertFalse(names.contains("StreamableHttpService"),
                "mcp " + MCP_PRE_STREAMABLE_HTTP_VERSION + " does not declare StreamableHttpService, so the"
                        + " metadata's entry must be skipped: " + names);
        Assert.assertFalse(names.contains("StreamableHttpAdvancedService"),
                "mcp " + MCP_PRE_STREAMABLE_HTTP_VERSION + " does not declare StreamableHttpAdvancedService,"
                        + " so the metadata's entry must be skipped: " + names);

        for (JsonElement element : services) {
            Assert.assertEquals(element.getAsJsonObject().getAsJsonObject("listener")
                            .get("name").getAsString(), "mcp:Listener",
                    "The metadata's StreamableHttpListener is absent from mcp "
                            + MCP_PRE_STREAMABLE_HTTP_VERSION + " and must fall back to the real class");
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
        // CopilotLibraryManager Gson-round-trips every service through the Service model class.
        // mcp's marker Service legitimately has no methods: the model keeps methods == null and the
        // re-serialized JSON omits the key — the shape the TS renderer's `?? []` guards handle.
        JsonArray services = load(MCP);
        Set<String> declared = declaredNames(MCP);
        Gson gson = new Gson();
        for (JsonElement element : services) {
            io.ballerina.flowmodelgenerator.core.copilot.model.Service service =
                    gson.fromJson(element, io.ballerina.flowmodelgenerator.core.copilot.model.Service.class);
            JsonObject reSerialized = gson.toJsonTree(service).getAsJsonObject();
            if ("Service".equals(service.getName())) {
                Assert.assertNull(service.getMethods());
                Assert.assertFalse(reSerialized.has("methods"),
                        "A method-less fixed service must omit the methods key after the round trip");
            }
            if ("AdvancedService".equals(service.getName())) {
                Assert.assertEquals(service.getMethods().size(), 2);
                Assert.assertTrue(reSerialized.has("methods"));
            }
            // The listener must survive the round trip intact and still name a real class; which
            // class that is depends on the resolved version, so it is checked, not hard-coded.
            assertListenerIsDeclared(
                    reSerialized.getAsJsonObject("listener").get("name").getAsString(), declared);
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

        // Exactly the vocabulary smb's compiler plugin admits, verified by compiling all seven against
        // smb 2.0.1: "smb listener only supports `onFile`, `onFileText`, `onFileJson`, `onFileXml`,
        // `onFileCsv`, `onFileDelete` and `onError` remote methods".
        //
        // `onFileChange` is deliberately absent. smb 1.0.2 declared a `WatchEvent` record and shipped no
        // compiler plugin at all, so the handler was legal; 2.0.1 removed the type AND rejects the name
        // outright (`ERROR invalid remote method name 'onFileChange'`). The document no longer declares it.
        Assert.assertEquals(methodNames(service), List.of("onFileText", "onFileJson", "onFileXml",
                "onFileCsv", "onFile", "onFileDelete", "onError"));

        JsonObject onFileJson = methodNamed(service, "onFileJson");
        Assert.assertEquals(paramNames(onFileJson), List.of("content", "caller", "fileInfo"));
        Assert.assertTrue(paramNamed(onFileJson, "caller").get("optional").getAsBoolean());
        assertInternalLink(paramNamed(onFileJson, "fileInfo"), "FileInfo");
        // Spec §5.1: smb's handlers are marker-type, so the document authors their descriptions.
        Assert.assertTrue(onFileJson.has("description"),
                "spec §5.1 makes `doc` the authored description of a marker-type handler");
    }

    @Test
    public void testWebsubSchemaServices() {
        JsonArray services = load("ballerina/websub");
        JsonObject service = serviceNamed(services, "SubscriberService");

        Assert.assertEquals(service.getAsJsonObject("listener").get("name").getAsString(),
                "websub:Listener");
        // onHubError is now emitted. It used to be silently dropped because the metadata declared its
        // param as "HubError", which the package does not declare — the veto that protected the prompt
        // from an uncompilable signature also made a real handler invisible. The document now says
        // "InternalHubError", which is what websub actually declares: verified by compiling a subscriber
        // service with `onHubError(websub:InternalHubError err)` (builds) against the same handler typed
        // `websub:HubError` ("unknown type 'HubError'"). The handler itself is genuinely part of the
        // contract — websub's plugin rejects an invented handler name but accepts this one.
        Assert.assertEquals(methodNames(service), List.of("onEventNotification",
                "onSubscriptionVerification", "onUnsubscriptionVerification",
                "onSubscriptionValidationDenied", "onHubError"));
        // The parameter is now AUTHORED by the document (spec §7 makes `name` required on a fixed slot)
        // rather than generated from the type, so it is `err` where the generator produced
        // `internalHubError`. The type is what matters and is unchanged.
        assertInternalLink(paramNamed(methodNamed(service, "onHubError"), "err"), "InternalHubError");

        // The metadata deliberately leaves these params unnamed: names are generated from the
        // declared type — idiomatic, compilable Ballerina.
        JsonObject onEventNotification = methodNamed(service, "onEventNotification");
        Assert.assertEquals(paramNames(onEventNotification), List.of("event"));
        assertInternalLink(paramNamed(onEventNotification, "event"),
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

    /**
     * smb and websub were never in the service-index {@code Annotation} table. They are no longer
     * a special case: every library's catalog is built from the Semantic Model, so these assert
     * the same path every other library takes, through {@code CopilotLibraryManager}.
     */
    @Test
    public void testNetNewLibraryAnnotationsReachTheCatalog() {
        List<Annotation> smb = libraryAnnotations("ballerina/smb");

        Annotation serviceConfig = annotationNamed(smb, "ServiceConfig", "SERVICE");
        Assert.assertNotNull(serviceConfig.getDescription(),
                "Semantic-Model annotations carry the library's doc comment");
        Assert.assertFalse(serviceConfig.getDescription().isEmpty(),
                "Semantic-Model annotations carry the library's doc comment");
        assertInternalTypeConstraint(serviceConfig, "SmbServiceConfig");

        // smb 2.0.1 declares FunctionConfig `on service remote function`, which the compiler reports as
        // RESOURCE (its constant for that Ballerina attach point — the enum has no SERVICE_REMOTE). It is
        // emitted verbatim; it is not reclassified.
        //
        // This assertion tracks whatever Central serves, unlike the service assertions below, because
        // `libraryAnnotations` goes through CopilotLibraryManager, which resolves the latest version
        // internally and offers no seam to pin one. smb 1.0.2 declared the same annotation `on function`
        // (reported FUNCTION); 2.0.1 moved it. Either value is a faithful report of the resolved package,
        // so what this pins is that the catalog states the compiler's answer rather than a guess.
        annotationNamed(smb, "FunctionConfig", "RESOURCE");

        annotationNamed(libraryAnnotations("ballerina/websub"), "SubscriberServiceConfig", "SERVICE");

        // kafka likewise has no curated rows. Its `on parameter` annotation now reaches the
        // catalog instead of being dropped for sitting outside SERVICE/OBJECT_METHOD.
        annotationNamed(libraryAnnotations("ballerinax/kafka"), "Payload", "PARAMETER");
    }

    private List<Annotation> libraryAnnotations(String libraryName) {
        List<Library> libraries =
                new CopilotLibraryManager().loadFilteredLibraries(new String[]{libraryName});
        if (libraries.isEmpty()) {
            throw new SkipException("Could not resolve package for " + libraryName);
        }
        List<Annotation> annotations = libraries.get(0).getAnnotations();
        Assert.assertNotNull(annotations, libraryName + " should expose an annotation catalog");
        return annotations;
    }

    private static Annotation annotationNamed(List<Annotation> annotations, String name,
                                              String attachmentPoint) {
        for (Annotation annotation : annotations) {
            if (name.equals(annotation.getName())
                    && attachmentPoint.equals(annotation.getAttachmentPoint())) {
                return annotation;
            }
        }
        StringBuilder present = new StringBuilder();
        for (Annotation annotation : annotations) {
            present.append(" @").append(annotation.getName())
                    .append('/').append(annotation.getAttachmentPoint());
        }
        Assert.fail("No @" + name + " on " + attachmentPoint + " in:" + present);
        return null;
    }

    private static void assertInternalTypeConstraint(Annotation annotation, String recordName) {
        Type typeConstraint = annotation.getTypeConstraint();
        Assert.assertNotNull(typeConstraint, "@" + annotation.getName() + " must carry a type constraint");
        Assert.assertEquals(typeConstraint.getName(), recordName);
        List<TypeLink> links = typeConstraint.getLinks();
        Assert.assertNotNull(links, "@" + annotation.getName() + " type constraint must be linked");
        Assert.assertEquals(links.size(), 1, "Expected exactly one link, got: " + links.size());
        Assert.assertEquals(links.get(0).getCategory(), "internal");
        Assert.assertEquals(links.get(0).getRecordName(), recordName);
    }

    private static JsonObject mapTypeConstraint(JsonObject annotation) {
        // Adapts an annotation's typeConstraint to the shape assertInternalLink expects.
        JsonObject wrapper = new JsonObject();
        wrapper.add("type", annotation.getAsJsonObject("typeConstraint"));
        return wrapper;
    }

    // ---- fallback & pinning --------------------------------------------------------------

    /**
     * Reports a library whose pinned version is no longer the one Central serves.
     *
     * <p>This is the only test in the class allowed to fail because of an upstream release, and it is the
     * reason the others are not. A trigger-metadata document describes a package at a point in time —
     * handler vocabulary, type names, annotation attach points — and a connector is free to change any of
     * them in a new release. When that happens the document has to be re-verified by a human; what this
     * guard supplies is the notification, naming the library and both versions.
     *
     * <p>It does not assert the document is still correct, because nothing here can: only compiling
     * against the new release answers that. It asserts that nobody is unknowingly testing against a
     * release the document was never checked against.
     *
     * <p>Under {@code --offline}, "latest" is the newest version in the local bala cache, so a build that
     * has never fetched a newer release simply passes. That is intended — the guard reports drift the
     * environment can actually observe, and does not fail for lack of network.
     */
    @Test
    public void testPinnedVersionsStillMatchLatest() {
        List<String> drifted = new ArrayList<>();
        for (Map.Entry<String, String> pin : PINNED_VERSIONS.entrySet()) {
            String[] parts = pin.getKey().split("/");
            Optional<Package> latest = PackageUtil.getModulePackage(
                    PackageUtil.getSampleProject(), parts[0], parts[1]);
            if (latest.isEmpty()) {
                // Unresolvable here means the environment cannot see the library at all, which every
                // other test in this class already reports as a skip rather than a failure.
                continue;
            }
            String resolved = latest.get().packageVersion().toString();
            if (!pin.getValue().equals(resolved)) {
                drifted.add(pin.getKey() + ": pinned " + pin.getValue() + ", latest " + resolved);
            }
        }
        drifted.sort(null);
        Assert.assertTrue(drifted.isEmpty(),
                "These libraries have moved on since their assertions were written. Re-verify each "
                        + "trigger-metadata document against the new release — handler vocabulary, "
                        + "parameter types, annotation attach points — then update PINNED_VERSIONS:\n  "
                        + String.join("\n  ", drifted));
    }

    @Test
    public void testNonSchemaDrivenLibraryStaysOnServiceIndex() {
        // asb is not schema-driven: the overload must produce exactly the SQLite-path output.
        String library = "ballerinax/asb";
        JsonArray viaOverload = load(library);
        JsonArray viaIndex = ServiceLoader.loadAllServices(library);
        Assert.assertEquals(viaOverload, viaIndex);
    }

    /**
     * The counterpart of the test above, and the property that lets the index fallback be withheld from a
     * schema-driven library.
     *
     * <p>{@code ballerina/ftp} has BOTH sources, so it is the case where substituting one for the other is
     * possible at all. The index entry describes the same handlers with strictly fewer facts — no §8
     * annotation obligations, no §6 constraints, no §9 binding rules, no presence markers — so serving it
     * in place of the document would not be a fallback but a silent downgrade, and the reader would have
     * no way to tell which one they were given.
     *
     * <p>Asserting both halves matters: that the two paths differ, and that the schema path is the richer
     * one. Difference alone would also be satisfied by the schema path being worse.
     */
    @Test
    public void testSchemaDrivenLibraryIsNeverSubstitutedByTheIndex() {
        String library = "ballerina/ftp";
        JsonArray viaOverload = load(library);
        JsonArray viaIndex = ServiceLoader.loadAllServices(library);
        Assert.assertNotEquals(viaOverload, viaIndex,
                "ftp is schema-driven; the index catalog must not be what the overload returns");

        JsonObject schemaService = serviceNamed(viaOverload, "Service");
        Assert.assertTrue(schemaService.has("annotations"),
                "the schema path carries §8 obligations the index has no column for");
        JsonObject onFileCsv = methodNamed(schemaService, "onFileCsv");
        Assert.assertTrue(onFileCsv.has("optional"),
                "the schema path states whether a handler must be implemented; the index does not");
        Assert.assertTrue(paramNamed(onFileCsv, "contents").has("binding"),
                "the schema path carries the §9 binding rule; the index has no equivalent");
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

    // ---- the curated overlay is additive on the schema path -------------------------------------

    @Test
    public void testACuratedOverlayNoLongerDeletesTheMetadataDocument() {
        // ballerina/http declares `type.name = "Service"` and generic-services.json declares a curated
        // entry of the same name, so the name collision used to drop the ENTIRE metadata-derived entry:
        // 8 method values, 3 path forms, 6 parameter slots, 7 annotation references (including the
        // corpus's only `attachPoint: "return"`) and a dataBindingRules rule reached the prompt nowhere.
        // The two sources are not substitutes — the document states facts, the curated file states the
        // conventions a document deliberately cannot carry — so a collision now merges instead of replacing.
        JsonObject service = serviceNamed(load("ballerina/http"), "Service");
        Assert.assertEquals(service.get("type").getAsString(), "fixed",
                "the metadata-derived entry must survive the collision, not be replaced by the curated one");
        Assert.assertTrue(service.has("handlerTemplates"),
                "http is an addMode:\"many\" catalog, so its shape must reach the wire: " + service);
        Assert.assertTrue(service.has("identifier"),
                "§3's required base path is stated by the document and must survive");
        Assert.assertTrue(service.has("instructions"),
                "...and the curated guidance must be carried onto it, not discarded: " + service.keySet());
    }

    @Test
    public void testTheCuratedGuidanceIsTheLibrarysOwnServiceMarkdown() {
        JsonObject service = serviceNamed(load("ballerina/http"), "Service");
        String instructions = service.get("instructions").getAsString();
        Assert.assertTrue(instructions.contains("module level"),
                "the absorbed text must be ballerina/http's own service.md: " + instructions);
    }

    @Test
    public void testGraphqlRendersEveryWildcardShapeNotJustTheFirst() {
        // graphql declares three "*" options where spec §4 allows one: a query (resource/get), a mutation
        // (remote) and a subscription (resource/subscribe, returning a stream). They differ in kind,
        // accessor and return, so taking only the first deleted two thirds of the handler surface.
        JsonObject service = serviceNamed(load("ballerina/graphql"), "Service");
        JsonArray templates = service.getAsJsonArray("handlerTemplates");
        Assert.assertNotNull(templates, "graphql's wildcard shapes must reach the wire: " + service);
        Assert.assertEquals(templates.size(), 3, "every wildcard shape must survive: " + templates);

        List<String> operations = new ArrayList<>();
        for (JsonElement element : templates) {
            JsonObject template = element.getAsJsonObject();
            Assert.assertTrue(template.has("graphqlOperation"), "each shape names its operation: " + template);
            operations.add(template.get("graphqlOperation").getAsString());
        }
        Assert.assertEquals(operations, List.of("query", "mutation", "subscription"),
                "document order is preserved");
    }

    @Test
    public void testAnIndexDerivedEntryStillYieldsToTheCuratedOverlay() {
        // The additive rule is scoped to the schema path on purpose. An index row carries a listener and a
        // method list and nothing else — which is precisely why the curated prose was written — so there is
        // nothing in it worth preserving alongside, and the SQLite path keeps its replace semantics.
        for (String library : new String[]{"ballerina/http", "ballerina/graphql"}) {
            JsonArray viaIndex = ServiceLoader.loadAllServices(library);
            boolean hasGeneric = false;
            for (JsonElement element : viaIndex) {
                JsonObject svc = element.getAsJsonObject();
                if (svc.has("type") && "generic".equals(svc.get("type").getAsString())) {
                    hasGeneric = true;
                }
            }
            Assert.assertTrue(hasGeneric,
                    "the SQLite path must still emit the curated generic entry for " + library);
        }
    }
}
