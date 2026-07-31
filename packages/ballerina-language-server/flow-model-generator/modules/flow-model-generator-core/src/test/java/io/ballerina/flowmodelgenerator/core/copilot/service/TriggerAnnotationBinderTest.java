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
import io.ballerina.modelgenerator.commons.trigger.models.TriggerMetadataModel;
import io.ballerina.modelgenerator.commons.trigger.models.TriggerMetadataModel.Annotation;
import io.ballerina.modelgenerator.commons.trigger.models.TriggerMetadataModel.ServiceType;
import io.ballerina.modelgenerator.commons.trigger.models.TypeRef;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.Arrays;
import java.util.List;

/**
 * Tests {@link TriggerAnnotationBinder}'s document-driven logic: which annotation is linked to which
 * service type, how ids resolve to attachments, module qualification, and {@code presence} handling.
 *
 * <p>The binder is constructed with a {@code null} semantic model throughout, which exercises every
 * path except body generation (a body then resolves to {@code null}, i.e. a bare attachment). Body
 * generation needs a real resolved package and is covered end-to-end by
 * {@code CopilotSchemaServicesTest}.</p>
 *
 * @since 1.7.0
 */
public class TriggerAnnotationBinderTest {

    private static final String ORG = "ballerinax";
    private static final String PACKAGE = "testmod";

    // ---- construction helpers -------------------------------------------------------

    private static Annotation annotation(String id, String typeName, TypeRef.PackageInfo packageInfo,
                                         String attachPoint, List<String> appliesTo, String presence) {
        return new Annotation(id, new TypeRef(typeName, packageInfo), attachPoint, appliesTo, presence);
    }

    private static ServiceType serviceType(String id, String typeName, ServiceType.Handlers handlers,
                                           List<ServiceType.Rule> rules) {
        return new ServiceType(id, new TypeRef(typeName, null), false, false, false, null, handlers, rules);
    }

    private static ServiceType.HandlerOption option(String name, List<String> annotations,
                                                    List<ServiceType.Param> params) {
        return new ServiceType.HandlerOption(name, "remote", null, annotations, params, null,
                null, null, null, null, null);
    }

    private static ServiceType.Param param(String name, List<String> annotations) {
        return new ServiceType.Param(name, List.of(new TypeRef("string", null)), "optional", null, null,
                annotations);
    }

    private static ServiceType.Rule rule(String type, String annotationId, String field) {
        return new ServiceType.Rule("r", type,
                List.of(new ServiceType.Rule.RuleMember(annotationId, field, null, null, null)));
    }

    private static TriggerAnnotationBinder binder(List<Annotation> annotations,
                                                  List<ServiceType> serviceTypes) {
        return new TriggerAnnotationBinder(
                new TriggerMetadataModel(null, serviceTypes, annotations, null), null, ORG, PACKAGE);
    }

    private static JsonObject only(JsonArray array) {
        Assert.assertEquals(array.size(), 1, "Expected exactly one attachment in " + array);
        return array.get(0).getAsJsonObject();
    }

    // ---- service-type linkage -------------------------------------------------------

    @Test
    public void testAppliesToLinksOnlyTheNamedServiceTypes() {
        TriggerAnnotationBinder binder = binder(
                List.of(annotation("basic", "ServiceConfig", null, "service", List.of("a"), "optional"),
                        annotation("streamable", "StreamableConfig", null, "service", List.of("b"),
                                "optional")),
                List.of(serviceType("a", "A", null, null), serviceType("b", "B", null, null)));

        Assert.assertEquals(only(binder.forServiceType("a")).get("name").getAsString(), "ServiceConfig");
        Assert.assertEquals(only(binder.forServiceType("b")).get("name").getAsString(),
                "StreamableConfig");
        // A service type the document names nowhere gets nothing.
        Assert.assertTrue(binder.forServiceType("c").isEmpty());
    }

    @Test
    public void testRulesLinkageWhenAppliesToIsAbsent() {
        // The document omits appliesTo precisely when another reference already pins the annotation
        // down; a rule member is one such reference.
        TriggerAnnotationBinder binder = binder(
                List.of(annotation("cfg", "ServiceConfig", null, "service", null, "optional")),
                List.of(serviceType("a", "A", null, List.of(rule("allOf", "cfg", "path"))),
                        serviceType("b", "B", null, null)));

        Assert.assertEquals(only(binder.forServiceType("a")).get("name").getAsString(), "ServiceConfig");
        Assert.assertTrue(binder.forServiceType("b").isEmpty(),
                "A rule on service type 'a' must not link the annotation to 'b'");
    }

    @Test
    public void testUnreferencedServiceAnnotationAppliesToEveryServiceType() {
        TriggerAnnotationBinder binder = binder(
                List.of(annotation("cfg", "ServiceConfig", null, "service", null, "optional")),
                List.of(serviceType("a", "A", null, null), serviceType("b", "B", null, null)));

        Assert.assertEquals(only(binder.forServiceType("a")).get("name").getAsString(), "ServiceConfig");
        Assert.assertEquals(only(binder.forServiceType("b")).get("name").getAsString(), "ServiceConfig");
    }

    @Test
    public void testNonServiceAttachPointsNeverLandOnAServiceType() {
        TriggerAnnotationBinder binder = binder(
                List.of(annotation("tool", "Tool", null, "function", null, "optional"),
                        annotation("hdr", "Header", null, "parameter", null, "optional")),
                List.of(serviceType("a", "A", null, null)));
        Assert.assertTrue(binder.forServiceType("a").isEmpty(),
                "A function/parameter-point annotation belongs on a handler, not on the service");
    }

    // ---- presence -------------------------------------------------------------------

    @Test
    public void testRequiredPresenceIsMarked() {
        TriggerAnnotationBinder binder = binder(
                List.of(annotation("cfg", "ServiceConfig", null, "service", List.of("a"), "required")),
                List.of(serviceType("a", "A", null, null)));
        Assert.assertTrue(only(binder.forServiceType("a")).get("required").getAsBoolean());
    }

    @Test
    public void testOptionalPresenceIsNotMarked() {
        TriggerAnnotationBinder binder = binder(
                List.of(annotation("cfg", "ServiceConfig", null, "service", List.of("a"), "optional")),
                List.of(serviceType("a", "A", null, null)));
        Assert.assertFalse(only(binder.forServiceType("a")).has("required"));
    }

    /**
     * A {@code oneOf} rule offers its members as alternatives, so an annotation reached only that way
     * must not be reported as mandatory even when the registry marks it {@code required} — the
     * requirement is on the value, not on writing this particular annotation.
     */
    @Test
    public void testOneOfAlternativeIsNeverMarkedRequired() {
        TriggerAnnotationBinder oneOf = binder(
                List.of(annotation("cfg", "ServiceConfig", null, "service", null, "required")),
                List.of(serviceType("a", "A", null, List.of(rule("oneOf", "cfg", "path")))));
        Assert.assertFalse(only(oneOf.forServiceType("a")).has("required"),
                "A oneOf alternative must not be presented as mandatory");

        // The same registry entry under a non-oneOf rule stays required.
        TriggerAnnotationBinder allOf = binder(
                List.of(annotation("cfg", "ServiceConfig", null, "service", null, "required")),
                List.of(serviceType("a", "A", null, List.of(rule("allOf", "cfg", "path")))));
        Assert.assertTrue(only(allOf.forServiceType("a")).get("required").getAsBoolean());
    }

    // ---- module qualification --------------------------------------------------------

    @Test
    public void testSamePackageAnnotationIsStillModuleQualified() {
        // A binding is a template for user code, where the annotation is always written with its
        // module prefix, exactly like the service type and listener beside it.
        TriggerAnnotationBinder binder = binder(
                List.of(annotation("cfg", "ServiceConfig", null, "service", List.of("a"), "optional")),
                List.of(serviceType("a", "A", null, null)));
        Assert.assertEquals(only(binder.forServiceType("a")).get("module").getAsString(),
                ORG + "/" + PACKAGE);
    }

    @Test
    public void testCrossModuleAnnotationCarriesItsDeclaringModule() {
        TypeRef.PackageInfo http = new TypeRef.PackageInfo("ballerina", "http", "http", "2.16.5");
        TriggerAnnotationBinder binder = binder(
                List.of(annotation("hdr", "Header", http, "parameter", null, "optional")),
                List.of(serviceType("a", "A", null, null)));
        JsonObject header = only(binder.forIds(List.of("hdr")));
        Assert.assertEquals(header.get("module").getAsString(), "ballerina/http");
        Assert.assertEquals(header.get("name").getAsString(), "Header");
    }

    @Test
    public void testSubmoduleUsesItsOwnModuleName() {
        // The renderer derives the alias from the last segment, so the module name must be carried
        // through verbatim rather than collapsed to the package name.
        TypeRef.PackageInfo cdc =
                new TypeRef.PackageInfo("ballerinax", "cdc", "cdc.driver", "1.0.2");
        TriggerAnnotationBinder binder = binder(
                List.of(annotation("cfg", "ServiceConfig", cdc, "service", List.of("a"), "optional")),
                List.of(serviceType("a", "A", null, null)));
        Assert.assertEquals(only(binder.forServiceType("a")).get("module").getAsString(),
                "ballerinax/cdc.driver");
    }

    // ---- forIds ----------------------------------------------------------------------

    @Test
    public void testForIdsResolvesHandlerAndParameterBindings() {
        TriggerAnnotationBinder binder = binder(
                List.of(annotation("tool", "Tool", null, "function", null, "optional"),
                        annotation("hdr", "Header", null, "parameter", null, "optional")),
                List.of(serviceType("a", "A",
                        new ServiceType.Handlers(false, "many",
                                List.of(option("*", List.of("tool"), List.of(param(null, List.of("hdr")))))),
                        null)));
        Assert.assertEquals(only(binder.forIds(List.of("tool"))).get("name").getAsString(), "Tool");
        Assert.assertEquals(only(binder.forIds(List.of("hdr"))).get("name").getAsString(), "Header");
    }

    @Test
    public void testForIdsDedupsAndSkipsUnknownAndNullIds() {
        TriggerAnnotationBinder binder = binder(
                List.of(annotation("tool", "Tool", null, "function", null, "optional")),
                List.of(serviceType("a", "A", null, null)));

        Assert.assertEquals(binder.forIds(List.of("tool", "tool")).size(), 1, "Ids must be de-duplicated");
        Assert.assertTrue(binder.forIds(List.of("nope")).isEmpty(), "An unknown id is skipped");
        Assert.assertTrue(binder.forIds(Arrays.asList((String) null)).isEmpty(), "A null id is skipped");
        Assert.assertTrue(binder.forIds(null).isEmpty());
        Assert.assertTrue(binder.forIds(List.of()).isEmpty());
    }

    @Test
    public void testMalformedRegistryEntriesAreIgnored() {
        TriggerAnnotationBinder binder = binder(
                Arrays.asList(
                        null,
                        annotation(null, "Nameless", null, "service", List.of("a"), "optional"),
                        annotation("noType", null, null, "service", List.of("a"), "optional"),
                        new Annotation("noTypeRef", null, "service", List.of("a"), "optional")),
                List.of(serviceType("a", "A", null, null)));
        Assert.assertTrue(binder.forServiceType("a").isEmpty(),
                "An entry without an id or a type name cannot be emitted");
    }

    @Test
    public void testEmptyDocumentYieldsNothing() {
        TriggerAnnotationBinder empty = binder(null, null);
        Assert.assertTrue(empty.forServiceType("a").isEmpty());
        Assert.assertTrue(empty.forIds(List.of("anything")).isEmpty());
    }

    /** No body can be generated without a semantic model, and a bare attachment is then correct. */
    @Test
    public void testNoBodyIsEmittedWhenTheDeclaringModelIsUnavailable() {
        TriggerAnnotationBinder binder = binder(
                List.of(annotation("cfg", "ServiceConfig", null, "service", List.of("a"), "optional")),
                List.of(serviceType("a", "A", null, null)));
        Assert.assertFalse(only(binder.forServiceType("a")).has("value"),
                "A value must never be invented when the constraint type cannot be read");
    }
}
