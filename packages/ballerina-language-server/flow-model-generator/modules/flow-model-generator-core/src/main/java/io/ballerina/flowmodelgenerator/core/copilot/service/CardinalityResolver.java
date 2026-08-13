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

/**
 * Owns <b>the spec's attachment cardinality</b>: how many listeners one service may attach to, how many
 * services one listener may host, and whether two of those may be the same service type.
 *
 * <p><b>Two of the three facts moved onto the listener in v1.0.</b> The old
 * {@code serviceTypes[].multipleServicesPerListenerAllowed} conflated two questions that {@code sap.jco}
 * answers differently: its one listener hosts an {@code IDocService} <i>and</i> an {@code RfcService}
 * ({@code multipleServicesAllowed: true}) while forbidding two of either
 * ({@code multipleServicesOfSameTypeAllowed: false}). The old shape could state one or the other, never both.
 *
 * <p>A pure passthrough of the document's two booleans. Which of them is worth <i>stating</i> is
 * {@link CardinalityAspect}'s omission rule, so this resolver stays the single place the spec's meaning
 * lives and the aspect stays the single place the editorial judgement lives.
 *
 * <p><b>Absent is not {@code false}.</b> Both fields are boxed in
 * {@link TriggerMetadataModel.ServiceType}, so a document that omits a key yields {@code null}. Since the
 * consumer states only the prohibition, reading an omission as {@code false} would invent a restriction the
 * document never made. Only an explicit {@code false} is a prohibition here, and
 * {@code CardinalityCheck} already reports the omission.
 *
 * @since 1.7.0
 */
final class CardinalityResolver {

    private CardinalityResolver() {
        // Prevent instantiation
    }

    /**
     * The spec's three cardinality answers for one (service type x listener) pair.
     *
     * @param multipleListeners           whether one service instance may attach to more than one listener
     *                                    at once ({@code service X on l1, l2 {}}). From the service type
     * @param multipleServices            whether one listener instance may host more than one service at
     *                                    all. From the listener
     * @param multipleServicesOfSameType  whether two of those services may be of this same service type.
     *                                    From the listener, and meaningful only when
     *                                    {@code multipleServices} is true — the spec omits the key entirely
     *                                    when it is not, "since one service at most already rules it out"
     */
    record Cardinality(boolean multipleListeners, boolean multipleServices,
                       boolean multipleServicesOfSameType) {
    }

    /**
     * Reads the cardinality of one service type paired with one listener.
     *
     * @param serviceType the service type; may be {@code null}
     * @param listener    the listener it is paired with; may be {@code null}
     * @return the cardinality; a {@code null} input reads as fully permissive, which states nothing
     */
    static Cardinality resolve(TriggerMetadataModel.ServiceType serviceType,
                               TriggerMetadataModel.Listener listener) {
        boolean multipleServices = listener == null
                || permissiveUnlessForbidden(listener.multipleServicesAllowed());
        // Derived, not defaulted: with at most one service on the listener, two of the same type is
        // already impossible, and the spec accordingly forbids the document from stating the key at all.
        // Reading it as permissive there would emit a note contradicting the one above it.
        boolean sameType = multipleServices
                && (listener == null
                        || permissiveUnlessForbidden(listener.multipleServicesOfSameTypeAllowed()));
        return new Cardinality(
                serviceType == null || permissiveUnlessForbidden(serviceType.multipleListenersAllowed()),
                multipleServices,
                sameType);
    }

    /**
     * Reads one cardinality key, treating an absent value as permissive.
     *
     * <p>Only an explicit {@code false} states a prohibition. {@code null} — the key is not in the document —
     * states nothing, and must not be read as {@code false}: a consumer that emits a note only on the
     * restrictive value would then manufacture a restriction out of an omission.
     *
     * @param declared the document's value; {@code null} when the key is absent
     * @return whether the document permits it
     */
    private static boolean permissiveUnlessForbidden(Boolean declared) {
        return !Boolean.FALSE.equals(declared);
    }
}
