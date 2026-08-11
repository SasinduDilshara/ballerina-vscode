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
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Conformance tests for <b>spec §9 {@code params[].dataBinding}</b> at the variant level, written against
 * the spec text and its two worked corpus examples.
 *
 * @since 1.7.0
 */
public class DataBindingResolverTest {

    private static final String PKG = "kafka";
    private static final Predicate<String> KAFKA_TYPES =
            Set.of("AnydataConsumerRecord", "BytesConsumerRecord")::contains;
    private static final Predicate<String> NO_TYPES = name -> false;

    @Test
    public void testAnAbsentBindingResolvesToNothing() {
        Assert.assertTrue(DataBindingResolver.resolve(null, PKG, NO_TYPES, name -> List.of()).isEmpty());
    }

    @Test
    public void testAnEmptyTypedescListResolvesToNothing() {
        Assert.assertTrue(DataBindingResolver.resolve(new TriggerMetadataModel.DataBinding(List.of()),
                PKG, NO_TYPES, name -> List.of()).isEmpty());
    }

    @Test
    public void testKafkaStyleVariantsAreBothKept() {
        // Spec §9's kafka example: two variants over the SAME bound, distinguished only by shape -- one an
        // array of bare values, one an array of envelope-including records. The old rule-level
        // `cardinality` flag could not express "batched, and included per element"; this is the case that
        // proves the new shape does.
        Optional<DataBindingResolver.BindingSpec> spec = DataBindingResolver.resolve(
                binding(
                        variant("anydata", List.of("AnydataConsumerRecord"),
                                arrayOf("bare", null, null)),
                        variant("anydata", null,
                                arrayOf("included", "AnydataConsumerRecord", List.of("value")))),
                PKG, KAFKA_TYPES, name -> List.of("key", "value", "offset"));

        Assert.assertTrue(spec.isPresent());
        List<DataBindingResolver.Variant> variants = spec.get().variants();
        Assert.assertEquals(variants.size(), 2);

        Assert.assertEquals(variants.get(0).constraint(), "anydata");
        Assert.assertEquals(variants.get(0).excludes(), List.of("kafka:AnydataConsumerRecord"));
        Assert.assertFalse(variants.get(0).shapes().get(0).embedsEnvelope());
        Assert.assertTrue(variants.get(0).shapes().get(0).isBatched());

        ShapeResolver.ResolvedShape included = variants.get(1).shapes().get(0);
        Assert.assertTrue(included.embedsEnvelope());
        Assert.assertEquals(included.envelope(), "kafka:AnydataConsumerRecord");
        Assert.assertEquals(included.bindableFields(), List.of("value"));
        // Spec §9: fixedFields is derived, never restated -- the envelope's fields minus the bindable ones.
        Assert.assertEquals(included.fixedFields(), List.of("key", "offset"));
    }

    @Test
    public void testFtpStyleVariantsShareShapesButDifferInBound() {
        // Spec §9's other worked case: "Two bounds that happen to share shapes are still two variants."
        // Collapsing them would have to pick one bound and silently delete the other.
        Optional<DataBindingResolver.BindingSpec> spec = DataBindingResolver.resolve(
                binding(
                        variant("string[]", null, arrayOf("bare", null, null),
                                streamOf("bare", "error?")),
                        variant("record {}", null, arrayOf("bare", null, null),
                                streamOf("bare", "error?"))),
                "ftp", NO_TYPES, name -> List.of());

        Assert.assertTrue(spec.isPresent());
        List<DataBindingResolver.Variant> variants = spec.get().variants();
        Assert.assertEquals(variants.size(), 2);
        Assert.assertEquals(variants.get(0).constraint(), "string[]");
        Assert.assertEquals(variants.get(1).constraint(), "record {}");
        Assert.assertEquals(variants.get(0).shapes().size(), 2);
        Assert.assertEquals(variants.get(0).shapes().get(1).completionType(), "error?");
    }

    @Test
    public void testAVariantWithNoBoundIsSkipped() {
        // A variant that constrains nothing offers no type for a consumer to name.
        Assert.assertTrue(DataBindingResolver.resolve(
                binding(new TriggerMetadataModel.TypedescVariant(null, null,
                        List.of(shape("bare", null, null, null, null)))),
                PKG, NO_TYPES, name -> List.of()).isEmpty());
    }

    @Test
    public void testAVariantWithNoReadableShapeIsSkipped() {
        // The bound is known but no way of embedding it is, which describes no declarable type.
        Assert.assertTrue(DataBindingResolver.resolve(
                binding(new TriggerMetadataModel.TypedescVariant(new TypeRef("anydata", null), null,
                        List.of(shape(null, null, null, null, null)))),
                PKG, NO_TYPES, name -> List.of()).isEmpty());
    }

    @Test
    public void testFixedFieldsIsEmptyWhenTheEnvelopeIsNotIntrospectable() {
        // Without a compiled package behind it the complement cannot be derived, and a consumer must not
        // claim to know which fields are pinned.
        Optional<DataBindingResolver.BindingSpec> spec = DataBindingResolver.resolve(
                binding(variant("anydata", null,
                        shape("included", null, "AnydataConsumerRecord", List.of("value"), null))),
                PKG, KAFKA_TYPES, name -> List.of());
        Assert.assertTrue(spec.get().variants().get(0).shapes().get(0).fixedFields().isEmpty());
    }

    // ---- fixtures --------------------------------------------------------------------

    private static TriggerMetadataModel.DataBinding binding(
            TriggerMetadataModel.TypedescVariant... variants) {
        return new TriggerMetadataModel.DataBinding(List.of(variants));
    }

    private static TriggerMetadataModel.TypedescVariant variant(String constraint, List<String> excludes,
                                                                TriggerMetadataModel.Shape... shapes) {
        return new TriggerMetadataModel.TypedescVariant(new TypeRef(constraint, null),
                excludes == null ? null : excludes.stream().map(n -> new TypeRef(n, null)).toList(),
                List.of(shapes));
    }

    private static TriggerMetadataModel.Shape arrayOf(String element, String envelope,
                                                      List<String> bindableFields) {
        return shape(TriggerMetadataModel.Shape.FORM_ARRAY, element, envelope, bindableFields, null);
    }

    private static TriggerMetadataModel.Shape streamOf(String element, String completionType) {
        return shape(TriggerMetadataModel.Shape.FORM_STREAM, element, null, null, completionType);
    }

    private static TriggerMetadataModel.Shape shape(String form, String element, String envelope,
                                                    List<String> bindableFields, String completionType) {
        return new TriggerMetadataModel.Shape(form, element,
                envelope == null ? null : new TypeRef(envelope, null), bindableFields,
                completionType == null ? null : List.of(new TypeRef(completionType, null)));
    }
}
