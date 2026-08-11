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
 * Owns <b>spec §3's {@code multipleListenersAllowed} and {@code multipleServicesPerListenerAllowed}</b>:
 * how many listeners one service may attach to, and how many services of this type one listener may host.
 *
 * <p>A pure passthrough of the document's two booleans. Which of them is worth <i>stating</i> is not
 * decided here — that is {@link CardinalityAspect}'s omission rule — so this resolver stays the single
 * place the spec's meaning lives and the aspect stays the single place the editorial judgement lives.
 *
 * <p><b>Absent is not {@code false}.</b> Both fields are boxed in
 * {@link TriggerMetadataModel.ServiceType}, so a document that omits a key yields {@code null} rather than
 * deserializing to {@code false}. That distinction is the whole point: the consumer states only the
 * prohibition, so reading an omission as {@code false} would invent a restriction the document never made
 * — the tri-state defect spec §5's {@code presence} already had, in reverse. Only an explicit
 * {@code false} is a prohibition here. The schema requires both keys and {@code CardinalityCheck} reports
 * an omission, so a document reaching this resolver with {@code null} is already a reported defect; this
 * class simply refuses to compound it with a fabricated claim.
 *
 * @since 1.7.0
 */
final class CardinalityResolver {

    private CardinalityResolver() {
        // Prevent instantiation
    }

    /**
     * Spec §3's two cardinality answers for one service type.
     *
     * @param multipleListeners           whether one service instance may attach to more than one listener
     *                                    at once ({@code service X on l1, l2 {}})
     * @param multipleServicesPerListener whether one listener may host more than one service of this type
     *                                    at once
     */
    record Cardinality(boolean multipleListeners, boolean multipleServicesPerListener) {
    }

    /**
     * Reads a service type's cardinality.
     *
     * @param serviceType the service type; may be {@code null}
     * @return its cardinality; a {@code null} service type reads as fully permissive, which states nothing
     */
    static Cardinality resolve(TriggerMetadataModel.ServiceType serviceType) {
        if (serviceType == null) {
            return new Cardinality(true, true);
        }
        return new Cardinality(permissiveUnlessForbidden(serviceType.multipleListenersAllowed()),
                permissiveUnlessForbidden(serviceType.multipleServicesPerListenerAllowed()));
    }

    /**
     * Reads one cardinality key, treating an absent value as permissive.
     *
     * <p>Only an explicit {@code false} states a prohibition. {@code null} — the key is not in the
     * document — states nothing, and must not be read as {@code false}: a consumer that emits a note only
     * on the restrictive value would then manufacture a restriction out of an omission. That is the
     * mirror image of the tri-state defect spec §5's {@code presence} already had, and the reason both
     * fields are boxed.
     *
     * @param declared the document's value; {@code null} when the key is absent
     * @return whether the document permits it
     */
    private static boolean permissiveUnlessForbidden(Boolean declared) {
        return !Boolean.FALSE.equals(declared);
    }
}
