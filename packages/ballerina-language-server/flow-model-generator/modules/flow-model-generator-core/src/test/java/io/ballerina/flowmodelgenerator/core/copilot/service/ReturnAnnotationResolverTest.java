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
 * Conformance tests for <b>spec §8 at {@code attachPoint: "return"}</b>, written against the spec text.
 *
 * <p>Spec v1.0 gave return scope its own forward reference, {@code handlers.options[].returnAnnotations}.
 * The point of these tests is that the reference is <b>per handler</b>: selection used to be by attach
 * point, which is a document-wide question, so every return-pointed annotation attached to every handler.
 *
 * @since 1.7.0
 */
public class ReturnAnnotationResolverTest {

    private static final String HOME = "http";

    @Test
    public void testAReferencedReturnAnnotationIsResolved() {
        Assert.assertEquals(names(resolve(registry(), List.of("$cache"))), List.of("Cache"));
    }

    @Test
    public void testAHandlerReferencingNothingCarriesNoReturnAnnotation() {
        // The whole point of the per-handler list: a handler whose return is not cacheable states nothing,
        // where attach-point selection would have attached `$cache` to it anyway.
        Assert.assertTrue(resolve(registry(), null).isEmpty());
        Assert.assertTrue(resolve(registry(), List.of()).isEmpty());
    }

    @Test
    public void testOnlyReturnScopedAnnotationsAreResolved() {
        // `returns @http:Payload {...} T` is not a legal slot for a parameter-pointed annotation.
        AnnotationScopeResolver.Resolution resolution =
                ReturnAnnotationResolver.resolve(registry(), List.of("$payload"), HOME, null);
        Assert.assertTrue(resolution.refs().isEmpty());
        Assert.assertEquals(resolution.rejections().size(), 1);
    }

    @Test
    public void testAnUnresolvableIdIsRejectedRatherThanSilentlyDropped() {
        AnnotationScopeResolver.Resolution resolution =
                ReturnAnnotationResolver.resolve(registry(), List.of("$nope"), HOME, null);
        Assert.assertTrue(resolution.refs().isEmpty());
        Assert.assertEquals(resolution.rejections().size(), 1);
    }

    @Test
    public void testPresenceAndAttachPointAreCarried() {
        AnnotationRef ref = resolve(registry(), List.of("$cache")).get(0);
        Assert.assertFalse(ref.required());
        Assert.assertEquals(ref.attachPoint(), TriggerMetadataModel.Annotation.ATTACH_POINT_RETURN);
    }

    @Test
    public void testDocumentOrderIsPreserved() {
        Assert.assertEquals(names(resolve(registry(), List.of("$etag", "$cache"))),
                List.of("ETag", "Cache"));
    }

    // ---- fixtures --------------------------------------------------------------------

    private static List<AnnotationRef> resolve(AnnotationRegistry registry, List<String> ids) {
        return ReturnAnnotationResolver.resolve(registry, ids, HOME, null).refs();
    }

    private static List<String> names(List<AnnotationRef> refs) {
        return refs.stream().map(AnnotationRef::name).toList();
    }

    private static AnnotationRegistry registry() {
        return AnnotationRegistry.of(new TriggerMetadataModel("v1.0", null, null, List.of(
                annotation("$cache", "Cache", TriggerMetadataModel.Annotation.ATTACH_POINT_RETURN),
                annotation("$etag", "ETag", TriggerMetadataModel.Annotation.ATTACH_POINT_RETURN),
                annotation("$payload", "Payload",
                        TriggerMetadataModel.Annotation.ATTACH_POINT_PARAMETER)), null));
    }

    private static TriggerMetadataModel.Annotation annotation(String id, String name, String attachPoint) {
        return new TriggerMetadataModel.Annotation(id, new TypeRef(name, null), attachPoint,
                TriggerMetadataModel.Annotation.PRESENCE_OPTIONAL);
    }
}
