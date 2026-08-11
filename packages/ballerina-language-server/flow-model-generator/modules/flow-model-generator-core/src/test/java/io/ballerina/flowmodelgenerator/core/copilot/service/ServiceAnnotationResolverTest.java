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

import io.ballerina.modelgenerator.commons.trigger.models.TriggerMetadataModel;
import io.ballerina.modelgenerator.commons.trigger.models.TypeRef;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.List;

/**
 * Conformance tests for <b>spec §8 at {@code attachPoint: "service"}</b>, written against the spec text.
 *
 * <p>Spec v1.0 closed §8's "Residual gap": service scope gained {@code serviceTypes[].annotations}, so
 * selection is now the same by-id lookup every other attach point uses. These pin that, and pin the
 * absence of any fallback — an annotation nothing references must attach nowhere, because a second,
 * implicit way to attach is exactly what the forward-reference table replaced.
 *
 * @since 1.7.0
 */
public class ServiceAnnotationResolverTest {

    private static final String HOME = "testmod";

    @Test
    public void testAReferencedServiceAnnotationIsResolved() {
        List<AnnotationRef> refs = resolve(registry(), List.of("$serviceConfig"));
        Assert.assertEquals(names(refs), List.of("ServiceConfig"));
    }

    @Test
    public void testAnAnnotationNothingReferencesIsNotResolved() {
        // The load-bearing consequence of dropping `appliesTo`: with no reverse list and no fallback, an
        // unreferenced entry attaches nowhere. AnnotationRefCheck reports it against the document instead.
        Assert.assertTrue(resolve(registry(), null).isEmpty());
        Assert.assertTrue(resolve(registry(), List.of()).isEmpty());
    }

    @Test
    public void testReferencesSelectIndependentlyPerServiceType() {
        // What `appliesTo` used to express in reverse, and less precisely: two service types referencing
        // different entries from the same registry.
        Assert.assertEquals(names(resolve(registry(), List.of("$serviceConfig"))),
                List.of("ServiceConfig"));
        Assert.assertEquals(names(resolve(registry(), List.of("$otherConfig"))),
                List.of("OtherConfig"));
    }

    @Test
    public void testDocumentOrderIsPreserved() {
        // Order is the reference list's, not the registry's: it is what the service type states.
        Assert.assertEquals(names(resolve(registry(), List.of("$otherConfig", "$serviceConfig"))),
                List.of("OtherConfig", "ServiceConfig"));
    }

    @Test
    public void testOnlyServiceScopedAnnotationsAreResolved() {
        // A reference to a function- or parameter-pointed entry is rejected rather than emitted at the
        // wrong slot, where the compiler would not allow the attachment.
        AnnotationScopeResolver.Resolution resolution = ServiceAnnotationResolver.resolve(
                registry(), List.of("$payload"), HOME, null);
        Assert.assertTrue(resolution.refs().isEmpty());
        Assert.assertEquals(resolution.rejections().size(), 1);
    }

    @Test
    public void testAnUnresolvableIdIsRejectedRatherThanSilentlyDropped() {
        AnnotationScopeResolver.Resolution resolution = ServiceAnnotationResolver.resolve(
                registry(), List.of("$nope"), HOME, null);
        Assert.assertTrue(resolution.refs().isEmpty());
        Assert.assertEquals(resolution.rejections().size(), 1);
    }

    @Test
    public void testPresenceDistinguishesRequiredFromOptional() {
        Assert.assertTrue(resolve(registry(), List.of("$requiredConfig")).get(0).required());
        Assert.assertFalse(resolve(registry(), List.of("$serviceConfig")).get(0).required());
    }

    @Test
    public void testAnUnrecognisedPresenceIsNotTreatedAsRequired() {
        // An unrecognised vocabulary term must not silently assert that generated code is obliged to carry
        // an annotation.
        AnnotationRegistry registry = registryOf(
                annotation("$odd", "Odd", TriggerMetadataModel.Annotation.ATTACH_POINT_SERVICE, "mandatory"));
        Assert.assertFalse(resolve(registry, List.of("$odd")).get(0).required());
    }

    @Test
    public void testAHomeModuleAnnotationCarriesNoModule() {
        // A home-module annotation takes the listener's alias at render time, so carrying a module here
        // would make the renderer prefix it twice.
        Assert.assertNull(resolve(registry(), List.of("$serviceConfig")).get(0).module());
    }

    @Test
    public void testCrossModuleAnnotationCarriesItsOwnModule() {
        // mssql's service annotation belongs to ballerinax/cdc; rendering it with mssql's alias would not
        // compile.
        AnnotationRegistry registry = registryOf(new TriggerMetadataModel.Annotation("$cdc",
                new TypeRef("ServiceConfig", new TypeRef.PackageInfo("ballerinax", "cdc", "cdc", "1.3.2")),
                TriggerMetadataModel.Annotation.ATTACH_POINT_SERVICE,
                TriggerMetadataModel.Annotation.PRESENCE_REQUIRED));
        Assert.assertEquals(resolve(registry, List.of("$cdc")).get(0).module(), "ballerinax/cdc");
    }

    @Test
    public void testAnAnnotationDeclaringTheHomeModuleExplicitlyIsNotForeign() {
        // sap.jco writes packageInfo on its own types. Cross-module-ness is judged by comparing the module
        // against home, not by the mere presence of coordinates.
        AnnotationRegistry registry = registryOf(new TriggerMetadataModel.Annotation("$own",
                new TypeRef("ServiceConfig",
                        new TypeRef.PackageInfo("ballerinax", HOME, HOME, "1.0.0")),
                TriggerMetadataModel.Annotation.ATTACH_POINT_SERVICE,
                TriggerMetadataModel.Annotation.PRESENCE_OPTIONAL));
        Assert.assertNull(resolve(registry, List.of("$own")).get(0).module());
    }

    @Test
    public void testAnEntryNamingNoAnnotationIsSkippedRatherThanEmittedNameless() {
        AnnotationRegistry registry = registryOf(new TriggerMetadataModel.Annotation("$nameless", null,
                TriggerMetadataModel.Annotation.ATTACH_POINT_SERVICE,
                TriggerMetadataModel.Annotation.PRESENCE_OPTIONAL));
        Assert.assertTrue(resolve(registry, List.of("$nameless")).isEmpty());
    }

    @Test
    public void testTheAttachPointIsCarriedOnEveryReference() {
        Assert.assertEquals(resolve(registry(), List.of("$serviceConfig")).get(0).attachPoint(),
                TriggerMetadataModel.Annotation.ATTACH_POINT_SERVICE);
    }

    @Test
    public void testABlankReferenceNamesNothingAndIsIgnored() {
        Assert.assertTrue(resolve(registry(), List.of("  ")).isEmpty());
    }

    // ---- fixtures --------------------------------------------------------------------

    private static List<AnnotationRef> resolve(AnnotationRegistry registry, List<String> ids) {
        return ServiceAnnotationResolver.resolve(registry, ids, HOME, null).refs();
    }

    private static List<String> names(List<AnnotationRef> refs) {
        return refs.stream().map(AnnotationRef::name).toList();
    }

    private static AnnotationRegistry registry() {
        return registryOf(
                annotation("$serviceConfig", "ServiceConfig",
                        TriggerMetadataModel.Annotation.ATTACH_POINT_SERVICE,
                        TriggerMetadataModel.Annotation.PRESENCE_OPTIONAL),
                annotation("$otherConfig", "OtherConfig",
                        TriggerMetadataModel.Annotation.ATTACH_POINT_SERVICE,
                        TriggerMetadataModel.Annotation.PRESENCE_OPTIONAL),
                annotation("$requiredConfig", "RequiredConfig",
                        TriggerMetadataModel.Annotation.ATTACH_POINT_SERVICE,
                        TriggerMetadataModel.Annotation.PRESENCE_REQUIRED),
                annotation("$payload", "Payload",
                        TriggerMetadataModel.Annotation.ATTACH_POINT_PARAMETER,
                        TriggerMetadataModel.Annotation.PRESENCE_OPTIONAL));
    }

    private static AnnotationRegistry registryOf(TriggerMetadataModel.Annotation... annotations) {
        return AnnotationRegistry.of(
                new TriggerMetadataModel("v1.0", null, null, List.of(annotations), null));
    }

    private static TriggerMetadataModel.Annotation annotation(String id, String name, String attachPoint,
                                                              String presence) {
        return new TriggerMetadataModel.Annotation(id, new TypeRef(name, null), attachPoint, presence);
    }
}
