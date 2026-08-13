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
 * Conformance tests for <b>spec §4 {@code handlers}</b> and <b>§5.1 {@code addMode}</b>, written against the
 * spec text rather than the implementation.
 *
 * <p>Spec statement pinned: {@code backedByConcreteType} — "{@code true} means the type's own methods are
 * the handlers … {@code false} means {@code options} is the only source of truth." Which of the two a
 * service type is decides where every handler, parameter name and description comes from, so it is the
 * single most consequential branch in the loader.
 *
 * <p>The second half pins what <b>this</b> spec revision changed: {@code addMode} moved from the
 * {@code handlers} block onto each option (§5.1), so a service type is no longer wholly fixed-name or wholly
 * open-ended. The three shapes that used to be degradations are now just documents.
 *
 * <p>The concrete branch resolves against a compiled package and is covered end-to-end by
 * {@code CopilotSchemaServicesTest}; what is pinned here is the classification itself, which is pure.
 *
 * @since 1.7.0
 */
public class HandlerCatalogResolverTest {

    @Test
    public void testBackedByConcreteTypeMeansTheTypeIsTheSourceOfTruth() {
        // trigger.github's shape: `concrete: true` with `backedByConcreteType: true` and no options.
        Assert.assertTrue(HandlerCatalogResolver.isConcrete(serviceType(true, handlers(true))));
    }

    @Test
    public void testEitherConcreteFlagAloneIsEnough() {
        // The two flags say the same thing from different angles; a document setting only one is still
        // unambiguous, and treating it as a marker type would discard the type's real methods.
        Assert.assertTrue(HandlerCatalogResolver.isConcrete(serviceType(true, handlers(false))));
        Assert.assertTrue(HandlerCatalogResolver.isConcrete(serviceType(false, handlers(true))));
    }

    @Test
    public void testMarkerTypeIsNotConcrete() {
        // kafka's shape: the type declares no methods, so `options` is the only source of truth.
        Assert.assertFalse(HandlerCatalogResolver.isConcrete(
                serviceType(false, handlers(false, subset("onEvent")))));
    }

    @Test
    public void testMissingHandlersBlockIsTreatedAsConcrete() {
        // With nothing to enumerate, the only possible source of truth is the type itself. Treating it as
        // a marker type would emit a service with no handlers at all.
        Assert.assertTrue(HandlerCatalogResolver.isConcrete(serviceType(false, null)));
    }

    @Test
    public void testMarkerTypeResolvesToItsDocumentedOptions() {
        // §4: for a marker type, `options` is the only source of truth — and it is passed through whole,
        // in document order, because §7 states "Array order is meaningful".
        TriggerMetadataModel.ServiceType.HandlerOption first = subset("onConsumerRecord");
        TriggerMetadataModel.ServiceType.HandlerOption second = subset("onError");
        HandlerCatalogResolver.HandlerCatalog.Documented catalog =
                documented(serviceType(false, handlers(false, first, second)));

        Assert.assertEquals(catalog.named().size(), 2);
        Assert.assertSame(catalog.named().get(0), first, "Document order must be preserved");
        Assert.assertSame(catalog.named().get(1), second);
        // The counterweight to the `addMode: "many"` case: a `subset` option's name IS the handler name,
        // so nothing may suggest the author picks it. kafka's onConsumerRecord is real.
        Assert.assertTrue(catalog.templates().isEmpty(),
                "a `subset` option names its handler; only `many` leaves the naming to the author");
    }

    @Test
    public void testAnAbsentAddModeReadsAsSubset() {
        // Spec §5.1: "`subset` … The default when absent." Reading an omission as `many` would render every
        // fixed lifecycle handler in the corpus as commented guidance instead of a writable signature.
        HandlerCatalogResolver.HandlerCatalog.Documented catalog =
                documented(serviceType(false, handlers(false, option("onMessage", null))));
        Assert.assertEquals(catalog.named().size(), 1);
        Assert.assertTrue(catalog.templates().isEmpty());
    }

    @Test
    public void testAManyOptionIsATemplateRatherThanANamedHandler() {
        // §5.1: a `many` option is "a shape the user instantiates any number of times, always named `*`".
        // It is not a handler name a reader can write, so it must never join the named list.
        TriggerMetadataModel.ServiceType.HandlerOption wildcard = many("*");
        HandlerCatalogResolver.HandlerCatalog.Documented catalog =
                documented(serviceType(false, handlers(false, wildcard)));
        Assert.assertTrue(catalog.named().isEmpty());
        Assert.assertEquals(catalog.templates().size(), 1);
        // Everything §5 states about such a handler — kind, params, returns, annotations — lives on that
        // entry, so the entry itself is what the lower tiers must be given.
        Assert.assertSame(catalog.templates().get(0), wildcard);
    }

    @Test
    public void testNamedAndTemplateOptionsCoexistInOneCatalog() {
        // The case §5.1 was written for, and the reason `addMode` moved off the block: "fixed lifecycle
        // handlers alongside open user-named ones". Under the old block-level flag this document had to
        // choose, and whichever it chose deleted the other group.
        TriggerMetadataModel.ServiceType.HandlerOption onError = subset("onError");
        TriggerMetadataModel.ServiceType.HandlerOption shape = many("*");
        HandlerCatalogResolver.HandlerCatalog.Documented catalog =
                documented(serviceType(false, handlers(false, onError, shape)));
        Assert.assertEquals(catalog.named().size(), 1, "the named option must survive beside a template");
        Assert.assertSame(catalog.named().get(0), onError);
        Assert.assertEquals(catalog.templates().size(), 1);
        Assert.assertSame(catalog.templates().get(0), shape);
    }

    @Test
    public void testSeveralTemplatesAreAllKeptInDocumentOrder() {
        // ballerina/graphql declares three `many` entries: its query, mutation and subscription shapes,
        // each with a different `kind`, accessor and return. Taking only the first deleted two thirds of
        // the connector's handler surface; §5.1 makes all three legal and they are kept in document order.
        TriggerMetadataModel.ServiceType.HandlerOption query = many("*");
        TriggerMetadataModel.ServiceType.HandlerOption mutation = many("*");
        TriggerMetadataModel.ServiceType.HandlerOption subscription = many("*");
        List<TriggerMetadataModel.ServiceType.HandlerOption> templates =
                documented(serviceType(false, handlers(false, query, mutation, subscription))).templates();
        Assert.assertEquals(templates.size(), 3, "every template shape must survive");
        Assert.assertSame(templates.get(0), query);
        Assert.assertSame(templates.get(1), mutation);
        Assert.assertSame(templates.get(2), subscription);
    }

    @Test
    public void testGrpcStyleNamedOptionsUnderNoBlockFlagAreAllNamed() {
        // ballerina/grpc's shape, which used to be the headline degradation: four named options that the
        // block-level `addMode: "many"` forced to be read as templates. With the flag per option, each of
        // them says `subset` for itself, and they are simply four named handlers.
        HandlerCatalogResolver.HandlerCatalog.Documented catalog = documented(
                serviceType(false, handlers(false, subset("unary"), subset("serverStreaming"))));
        Assert.assertEquals(catalog.named().size(), 2);
        Assert.assertTrue(catalog.templates().isEmpty());
    }

    @Test
    public void testASubsetOptionWithNoNameIsDropped() {
        // A `subset` option's name is the whole of what a reader writes. Without one there is nothing to
        // emit, and keeping it would render a nameless method.
        HandlerCatalogResolver.HandlerCatalog.Documented catalog =
                documented(serviceType(false, handlers(false, option(null, null), subset("onMessage"))));
        Assert.assertEquals(catalog.named().size(), 1);
        Assert.assertEquals(catalog.named().get(0).name(), "onMessage");
    }

    // ---- degradations ----------------------------------------------------------------

    /**
     * Every documented catalog now degrades in no way, which is the headline of this spec revision.
     *
     * <p>The three shapes that used to report one — {@code many} with named options, several wildcards, and
     * a wildcard mixed with named options — are all legal under §5.1. The field survives because the shape
     * of the contract should not change with the corpus.
     */
    @Test
    public void testTheThreeFormerDegradationsAreNoLongerReported() {
        Assert.assertTrue(HandlerCatalogResolver.resolve(
                serviceType(false, handlers(false, subset("unary"), subset("serverStreaming"))),
                "Service", null).degradations().isEmpty());
        Assert.assertTrue(HandlerCatalogResolver.resolve(
                serviceType(false, handlers(false, many("*"), many("*"), many("*"))),
                "Service", null).degradations().isEmpty());
        Assert.assertTrue(HandlerCatalogResolver.resolve(
                serviceType(false, handlers(false, many("*"), subset("onMessage"))),
                "Service", null).degradations().isEmpty());
    }

    // ---- fixtures --------------------------------------------------------------------

    private static HandlerCatalogResolver.HandlerCatalog.Documented documented(
            TriggerMetadataModel.ServiceType serviceType) {
        HandlerCatalogResolver.HandlerCatalog catalog =
                HandlerCatalogResolver.resolve(serviceType, "Service", null).catalog();
        Assert.assertTrue(catalog instanceof HandlerCatalogResolver.HandlerCatalog.Documented,
                "Expected a documented catalog, got: " + catalog);
        return (HandlerCatalogResolver.HandlerCatalog.Documented) catalog;
    }

    private static TriggerMetadataModel.ServiceType.Handlers handlers(
            boolean backedByConcreteType, TriggerMetadataModel.ServiceType.HandlerOption... options) {
        return new TriggerMetadataModel.ServiceType.Handlers(backedByConcreteType, List.of(options));
    }

    private static TriggerMetadataModel.ServiceType serviceType(
            boolean concrete, TriggerMetadataModel.ServiceType.Handlers handlers) {
        return new TriggerMetadataModel.ServiceType("service", new TypeRef("Service", null), concrete, true,
                null, null, null, handlers, null);
    }

    private static TriggerMetadataModel.ServiceType.HandlerOption subset(String name) {
        return option(name, TriggerMetadataModel.ServiceType.HandlerOption.ADD_MODE_SUBSET);
    }

    private static TriggerMetadataModel.ServiceType.HandlerOption many(String name) {
        return option(name, TriggerMetadataModel.ServiceType.HandlerOption.ADD_MODE_MANY);
    }

    private static TriggerMetadataModel.ServiceType.HandlerOption option(String name, String addMode) {
        return new TriggerMetadataModel.ServiceType.HandlerOption(name, "remote", addMode, null, null,
                "optional", null, null, null, null, null, null);
    }
}
