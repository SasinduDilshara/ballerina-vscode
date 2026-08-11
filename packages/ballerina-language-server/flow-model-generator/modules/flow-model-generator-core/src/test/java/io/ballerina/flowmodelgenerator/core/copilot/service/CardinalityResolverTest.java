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
 * Conformance tests for <b>spec §3.1's attachment cardinality</b>, written against the spec text.
 *
 * <p>Spec v1.0 split the question three ways and moved two of the three onto the listener. These pin both
 * halves of that: where each fact is read from, and the tri-state reading that keeps an omission from
 * becoming a prohibition.
 *
 * @since 1.7.0
 */
public class CardinalityResolverTest {

    @Test
    public void testAllThreeFactsArePermissiveWhenTheDocumentStatesNothing() {
        // Every field is boxed precisely so an absent key stays null. A consumer states only prohibitions,
        // so reading an omission as `false` would invent a restriction the document never made.
        CardinalityResolver.Cardinality cardinality =
                CardinalityResolver.resolve(serviceType(null), listener(null, null));
        Assert.assertTrue(cardinality.multipleListeners());
        Assert.assertTrue(cardinality.multipleServices());
        Assert.assertTrue(cardinality.multipleServicesOfSameType());
    }

    @Test
    public void testMultipleListenersIsReadFromTheServiceType() {
        // Spec §3.1: "May one service attach to several listeners at once?" is the one fact that stays on
        // the service type, because it describes the service rather than the listener.
        Assert.assertFalse(CardinalityResolver.resolve(serviceType(false), listener(true, true))
                .multipleListeners());
        Assert.assertTrue(CardinalityResolver.resolve(serviceType(true), listener(false, null))
                .multipleListeners());
    }

    @Test
    public void testMultipleServicesIsReadFromTheListener() {
        // Spec §3.1: "May one listener instance host more than one service?" -- a property of the listener,
        // which is why v1.0 moved it there.
        Assert.assertFalse(CardinalityResolver.resolve(serviceType(true), listener(false, null))
                .multipleServices());
        Assert.assertTrue(CardinalityResolver.resolve(serviceType(true), listener(true, null))
                .multipleServices());
    }

    @Test
    public void testSameTypeIsReadFromTheListener() {
        // The SAP JCo case spec §3.1 is written around: one listener hosts an IDocService AND an
        // RfcService, but never two of either.
        CardinalityResolver.Cardinality cardinality =
                CardinalityResolver.resolve(serviceType(true), listener(true, false));
        Assert.assertTrue(cardinality.multipleServices());
        Assert.assertFalse(cardinality.multipleServicesOfSameType());
    }

    @Test
    public void testSameTypeIsDerivedFalseWhenTheListenerHostsOneServiceAtMost() {
        // Spec §2 forbids the document from stating `multipleServicesOfSameTypeAllowed` when
        // `multipleServicesAllowed` is false, "since one service at most already rules it out". Reading the
        // absent key as permissive there would emit a note contradicting the stronger one above it.
        CardinalityResolver.Cardinality cardinality =
                CardinalityResolver.resolve(serviceType(true), listener(false, null));
        Assert.assertFalse(cardinality.multipleServices());
        Assert.assertFalse(cardinality.multipleServicesOfSameType());
    }

    @Test
    public void testNullInputsReadAsFullyPermissive() {
        // A caller exercising the resolver without a document must not have restrictions invented for it.
        CardinalityResolver.Cardinality cardinality = CardinalityResolver.resolve(null, null);
        Assert.assertTrue(cardinality.multipleListeners());
        Assert.assertTrue(cardinality.multipleServices());
        Assert.assertTrue(cardinality.multipleServicesOfSameType());
    }

    // ---- fixtures --------------------------------------------------------------------

    private static TriggerMetadataModel.ServiceType serviceType(Boolean multipleListenersAllowed) {
        return new TriggerMetadataModel.ServiceType("$service", new TypeRef("Service", null), false,
                multipleListenersAllowed, null, null, null,
                new TriggerMetadataModel.ServiceType.Handlers(true, null), null);
    }

    private static TriggerMetadataModel.Listener listener(Boolean multipleServicesAllowed,
                                                          Boolean multipleServicesOfSameTypeAllowed) {
        return new TriggerMetadataModel.Listener(new TypeRef("Listener", null), null, List.of("$service"),
                multipleServicesAllowed, multipleServicesOfSameTypeAllowed, null, null);
    }
}
