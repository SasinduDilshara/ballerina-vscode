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
import io.ballerina.modelgenerator.commons.trigger.models.ValueSpec;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Optional;

/**
 * Conformance tests for <b>spec §5's {@code accessor} and {@code path}</b>, written against the spec text.
 *
 * <p>This spec revision replaced HTTP's {@code method}/{@code path} and GraphQL's
 * {@code accessor}/{@code fieldName} with one pair of slots described symmetrically: "Both are required for
 * {@code kind: "resource"} and neither applies to {@code kind: "remote"}." What is pinned here is that the
 * new {@code ValueSpec} reading distinguishes the three states a slot can be in — enumerated, open, absent —
 * because collapsing any two of them produces a note that misleads rather than merely under-informs.
 *
 * @since 1.10.0
 */
public class ResourceExtrasResolverTest {

    @Test
    public void testARemoteHandlerDeclaringNeitherSlotResolvesToNothing() {
        // §5: "neither applies to `kind: "remote"`". The overwhelmingly common case, and the one that must
        // not gain an empty accessor note.
        Assert.assertTrue(ResourceExtrasResolver.resolve(option(null, null)).isEmpty());
        Assert.assertTrue(ResourceExtrasResolver.resolve(null).isEmpty());
    }

    @Test
    public void testAnEnumeratedAccessorCarriesEveryLegalValue() {
        // http's shape: the accessor is one of a fixed set, and a reader needs the whole set to choose.
        ResourceExtrasResolver.ResourceExtras extras =
                resolve(spec("required", List.of("get", "post", "put")), spec("required", null));
        Assert.assertEquals(extras.accessorValues(), List.of("get", "post", "put"));
        Assert.assertFalse(extras.accessorOpen());
        Assert.assertTrue(extras.accessorRequired());
        Assert.assertTrue(extras.pathRequired());
    }

    @Test
    public void testTheFirstDeclaredValueIsTheOneWritten() {
        // Spec §1: "the first element is the codegen default". A generator writes one accessor, and picking
        // any other element would contradict the document's own ordering.
        Assert.assertEquals(resolve(spec("required", List.of("get", "post")), null).accessor(), "get");
    }

    @Test
    public void testAnOpenAccessorNamesNoValueAtAll() {
        // §5's `values: ["*"]`. Carrying the literal `*` through as a value would produce a note telling
        // the reader to write an accessor called `*`; the open flag is what lets a consumer say "any
        // accessor the language accepts" instead.
        ResourceExtrasResolver.ResourceExtras extras = resolve(spec("required", List.of("*")), null);
        Assert.assertTrue(extras.accessorOpen());
        Assert.assertTrue(extras.accessorValues().isEmpty(), "an open slot enumerates nothing");
        Assert.assertNull(extras.accessor(), "an open slot has no single default to write");
    }

    @Test
    public void testAnOptionalAccessorIsToldApartFromARequiredOne() {
        // The two states differ in what a reader must do, so `presence` cannot be flattened into "there is
        // an accessor slot". graphql's subscription accessor is required; an optional one is a suggestion.
        Assert.assertTrue(resolve(spec("required", List.of("get")), null).accessorRequired());
        Assert.assertFalse(resolve(spec("optional", List.of("get")), null).accessorRequired());
    }

    @Test
    public void testAPathOnlyHandlerStillResolves() {
        // A document may state the path slot alone. Requiring both before resolving anything would drop
        // the one fact it did state.
        Optional<ResourceExtrasResolver.ResourceExtras> extras =
                ResourceExtrasResolver.resolve(option(null, spec("required", null)));
        Assert.assertTrue(extras.isPresent());
        Assert.assertTrue(extras.get().pathRequired());
        Assert.assertNull(extras.get().accessor());
        Assert.assertFalse(extras.get().accessorRequired());
    }

    @Test
    public void testBlankValuesAreDropped() {
        // A blank accessor is not a legal method name; carrying it would make it the codegen default.
        Assert.assertEquals(resolve(spec("required", List.of("", "  ", "get")), null).accessorValues(),
                List.of("get"));
    }

    // ---- fixtures --------------------------------------------------------------------

    private static ResourceExtrasResolver.ResourceExtras resolve(ValueSpec accessor, ValueSpec path) {
        Optional<ResourceExtrasResolver.ResourceExtras> extras =
                ResourceExtrasResolver.resolve(option(accessor, path));
        Assert.assertTrue(extras.isPresent());
        return extras.get();
    }

    private static ValueSpec spec(String presence, List<String> values) {
        return new ValueSpec(presence, values);
    }

    private static TriggerMetadataModel.ServiceType.HandlerOption option(ValueSpec accessor, ValueSpec path) {
        return new TriggerMetadataModel.ServiceType.HandlerOption("onEvent", "resource", null, null, null,
                "optional", null, null, null, null, accessor, path);
    }
}
