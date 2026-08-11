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

import java.util.Arrays;
import java.util.Optional;
import java.util.List;

/**
 * Conformance tests for <b>Spec §2 {@code listeners[].services}</b> — "{@code serviceTypes[].id} values
 * this listener can host" — written against the spec text rather than the implementation.
 *
 * <p>All 13 corpus documents declare exactly one listener, so the multi-listener path is <b>latent</b>:
 * it is covered here with synthetic documents, which is the only way it can be covered at all. The
 * listener-class resolution half of the resolver needs a compiled package and is covered end-to-end by
 * {@code CopilotSchemaServicesTest}.
 *
 * @since 1.7.0
 */
public class ListenerPairingResolverTest {

    @Test
    public void testServiceTypeGoesToTheListenerThatNamesItsId() {
        // §2: `services` lists the ids a listener can host, so the id is what binds the two.
        TriggerMetadataModel.Listener http = listener("HttpListener", "restService");
        TriggerMetadataModel.Listener grpc = listener("GrpcListener", "rpcService");

        Assert.assertSame(ListenerPairingResolver.hostOf(List.of(http, grpc), serviceType("rpcService")),
                grpc);
        Assert.assertSame(ListenerPairingResolver.hostOf(List.of(http, grpc), serviceType("restService")),
                http);
    }

    @Test
    public void testOneListenerHostingSeveralServiceTypes() {
        // trigger.github's shape: a single listener naming every event service type it can host.
        TriggerMetadataModel.Listener only = listener("Listener", "issues", "push", "release");
        for (String id : List.of("issues", "push", "release")) {
            Assert.assertSame(ListenerPairingResolver.hostOf(List.of(only), serviceType(id)), only);
        }
    }

    @Test
    public void testUnmatchedIdFallsBackToTheFirstListener() {
        // A document whose `services` omits an id is incomplete, not unusable: the service type still has
        // to be placed somewhere, and the first listener is the only defensible default.
        TriggerMetadataModel.Listener first = listener("Listener", "other");
        TriggerMetadataModel.Listener second = listener("Second", "alsoOther");
        Assert.assertSame(ListenerPairingResolver.hostOf(List.of(first, second), serviceType("unlisted")),
                first);
    }

    @Test
    public void testAbsentServicesListFallsBackToTheFirstListener() {
        TriggerMetadataModel.Listener noServices =
                new TriggerMetadataModel.Listener(new TypeRef("Listener", null), null, null, null, null, null, null);
        Assert.assertSame(
                ListenerPairingResolver.hostOf(List.of(noServices), serviceType("service")), noServices);
    }

    @Test
    public void testServiceTypeWithNoIdFallsBackToTheFirstListener() {
        TriggerMetadataModel.Listener first = listener("Listener", "service");
        Assert.assertSame(ListenerPairingResolver.hostOf(List.of(first), serviceType(null)), first);
        Assert.assertSame(ListenerPairingResolver.hostOf(List.of(first), null), first);
    }

    @Test
    public void testFirstNamingListenerWinsWhenSeveralClaimTheSameId() {
        // The spec does not forbid two listeners claiming one id. Document order decides, so the outcome
        // is at least deterministic rather than dependent on iteration order.
        TriggerMetadataModel.Listener first = listener("First", "shared");
        TriggerMetadataModel.Listener second = listener("Second", "shared");
        Assert.assertSame(ListenerPairingResolver.hostOf(List.of(first, second), serviceType("shared")),
                first);
    }

    @Test
    public void testNullListenerEntryIsSkippedRatherThanMatched() {
        TriggerMetadataModel.Listener real = listener("Listener", "service");
        Assert.assertSame(
                ListenerPairingResolver.hostOf(Arrays.asList(real, null), serviceType("service")), real);
    }

    @Test
    public void testNoServiceTypesYieldsNoPairings() {
        Assert.assertTrue(ListenerPairingResolver.resolve(List.of(listener("Listener", "s")), null, null)
                .isEmpty());
        Assert.assertTrue(ListenerPairingResolver.resolve(List.of(), List.of(serviceType("s")), null)
                .isEmpty());
        Assert.assertTrue(ListenerPairingResolver.resolve(null, List.of(serviceType("s")), null)
                .isEmpty());
    }

    // ---- hostability: the question `hostOf` deliberately cannot answer ------------------

    @Test
    public void testAServiceTypeNoListenerNamesIsReportedUnhostable() {
        // The websocket case, which is the whole reason this predicate exists: two service types, one
        // listener, and the listener names only the first. The compiler rejects
        // `service websocket:Service on new websocket:Listener(...)` with "service type is not supported
        // by the listener", so `hostOf`'s first-listener fallback must not be read as a real pairing.
        TriggerMetadataModel.Listener onlyUpgrade = listener("Listener", "upgradeService");

        Assert.assertTrue(ListenerPairingResolver.isHostedByAnyListener(
                List.of(onlyUpgrade), serviceType("upgradeService")));
        Assert.assertFalse(ListenerPairingResolver.isHostedByAnyListener(
                List.of(onlyUpgrade), serviceType("service")));
    }

    @Test
    public void testAListenerStatingNoServicesConstrainsNothing() {
        // Spec §2 makes `services` the statement of what a listener can host; a listener that states none
        // has stated no restriction. Reading absence as prohibition would declare every service type of
        // such a document unattachable — the exact inversion `hostOf` already avoids for the same reason.
        TriggerMetadataModel.Listener unconstrained =
                new TriggerMetadataModel.Listener(new TypeRef("Listener", null), null, null, null, null, null, null);
        TriggerMetadataModel.Listener empty =
                new TriggerMetadataModel.Listener(new TypeRef("Listener", null), null, List.of(), null, null, null,
                        null);

        Assert.assertTrue(ListenerPairingResolver.isHostedByAnyListener(
                List.of(unconstrained), serviceType("anything")));
        Assert.assertTrue(ListenerPairingResolver.isHostedByAnyListener(
                List.of(empty), serviceType("anything")));
    }

    @Test
    public void testHostabilityIsAnswerdByAnyListenerNotOnlyTheFirst() {
        // A document may spread its service types across listeners; being named by the second is being
        // hosted just as much as being named by the first.
        TriggerMetadataModel.Listener first = listener("First", "a");
        TriggerMetadataModel.Listener second = listener("Second", "b");
        Assert.assertTrue(ListenerPairingResolver.isHostedByAnyListener(
                List.of(first, second), serviceType("b")));
        Assert.assertFalse(ListenerPairingResolver.isHostedByAnyListener(
                List.of(first, second), serviceType("c")));
    }

    @Test
    public void testAnUnidentifiedServiceTypeIsTrustedRatherThanDeclaredUnattachable() {
        // A service type with no id cannot be named by any `services` list, so treating the absence of a
        // match as a prohibition would silently change the shape of a document that merely omits an id.
        TriggerMetadataModel.Listener constrained = listener("Listener", "service");
        Assert.assertTrue(ListenerPairingResolver.isHostedByAnyListener(
                List.of(constrained), serviceType(null)));
        Assert.assertTrue(ListenerPairingResolver.isHostedByAnyListener(List.of(constrained), null));
        Assert.assertTrue(ListenerPairingResolver.isHostedByAnyListener(null, serviceType("service")));
    }

    // ---- the drop is attributable ------------------------------------------------------

    @Test
    public void testAServiceTypeDroppedForAnUnresolvableListenerCarriesAVeto() {
        // The hole this closes: the drop was a bare `continue`. When EVERY listener fails the loader logs a
        // warning, but when only some do, the affected service types vanished from the catalog with no veto,
        // no log line, and nothing a test could assert — while every other tier of this pipeline records an
        // attributable reason.
        ListenerPairingResolver.Pairings paired = ListenerPairingResolver.resolveWithDiagnostics(
                List.of(listener("GhostListener", "service")), List.of(serviceType("service")),
                name -> Optional.empty());

        Assert.assertTrue(paired.pairings().isEmpty());
        Assert.assertEquals(paired.vetoes().size(), 1);
        Veto veto = paired.vetoes().get(0);
        Assert.assertEquals(veto.specSection(), "§2");
        Assert.assertEquals(veto.aspectId(), "listenerPairing");
        // Attributed to the service type, because that is what disappears from the catalog.
        Assert.assertEquals(veto.subject(), "Service");
        Assert.assertTrue(veto.reason().contains("GhostListener"),
                "the reason must name the class the document declared: " + veto.reason());
    }

    @Test
    public void testEveryDroppedServiceTypeIsReportedIndividually() {
        // A partial failure is the case that was silent: one veto per dropped service type, so "why did
        // exactly this one disappear?" has an answer.
        ListenerPairingResolver.Pairings paired = ListenerPairingResolver.resolveWithDiagnostics(
                List.of(listener("GhostListener", "a", "b")),
                List.of(serviceType("a"), serviceType("b")),
                name -> Optional.empty());
        Assert.assertEquals(paired.vetoes().size(), 2);
    }

    @Test
    public void testAResolvableListenerRecordsNoVeto() {
        ListenerPairingResolver.Pairings paired = ListenerPairingResolver.resolveWithDiagnostics(
                List.of(listener("Listener", "service")), List.of(), name -> Optional.empty());
        Assert.assertTrue(paired.vetoes().isEmpty(), "nothing was dropped, so nothing is reported");
    }

    // ---- fixtures --------------------------------------------------------------------

    private static TriggerMetadataModel.Listener listener(String className, String... hostedIds) {
        return new TriggerMetadataModel.Listener(new TypeRef(className, null), null, List.of(hostedIds), null, null,
                null, null);
    }

    private static TriggerMetadataModel.ServiceType serviceType(String id) {
        return new TriggerMetadataModel.ServiceType(id, new TypeRef("Service", null), false, true, null, null, null,
                new TriggerMetadataModel.ServiceType.Handlers(false, List.of()), null);
    }
}
