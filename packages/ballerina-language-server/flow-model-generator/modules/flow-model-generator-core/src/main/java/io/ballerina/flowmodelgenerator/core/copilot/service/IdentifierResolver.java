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

import io.ballerina.modelgenerator.commons.trigger.models.PresenceForm;

import java.util.List;
import java.util.Optional;

/**
 * Owns <b>the spec {@code serviceTypes[].identifier}</b>: the slot between {@code service} and
 * {@code on new …}, and whether the generated service must fill it.
 *
 * <p>The spec keeps the key present "only when genuinely consulted", so an absent key means the connector
 * ignores whatever is written there rather than that the slot defaults to something. It therefore yields
 * {@link Optional#empty()} and the renderer emits nothing at all, which is what {@code ftp}, {@code kafka},
 * {@code mssql.cdc} and the concrete-service-type libraries need.
 *
 * <p><b>Vocabulary.</b> The spec enumerates exactly two forms for this slot: {@code basePath} and
 * {@code stringLiteral}. An unrecognised form maps to {@link IdentifierForm#UNKNOWN} rather than throwing:
 * the renderer then states that the slot is consulted without inventing a placeholder whose syntax it
 * cannot know.
 *
 * <p><b>Multiple forms.</b> A {@code form} array may list several shapes, and {@link IdentifierSlot#forms()}
 * keeps all of them so the renderer can say which are legal. The rendered placeholder follows the first, per
 * The spec's codegen-default convention. No corpus document lists more than one.
 *
 * @since 1.7.0
 */
final class IdentifierResolver {

    private IdentifierResolver() {
        // Prevent instantiation
    }

    /**
     * A service type's resolved identifier slot.
     *
     * <p>{@code forms} holds the document's own tokens rather than a resolved enum, so that an unrecognised
     * token survives to the wire and the renderer can name it in the note it emits. The spec's "first element
     * is the codegen default" makes {@code forms.get(0)} the form the rendered placeholder follows.
     *
     * @param required whether the slot must be filled (the spec {@code presence: "required"})
     * @param forms    every legal form as declared, in document order; never empty
     */
    record IdentifierSlot(boolean required, List<String> forms) {
    }

    /**
     * Resolves a service type's {@code identifier}.
     *
     * @param identifier the declared slot; {@code null} when the document omits the key
     * @return the resolved slot, or empty when the connector does not consult the identifier
     */
    static Optional<IdentifierSlot> resolve(PresenceForm identifier) {
        if (identifier == null) {
            return Optional.empty();
        }
        List<String> forms = identifier.form() == null ? List.of()
                : identifier.form().stream()
                        .filter(form -> form != null && !form.isBlank())
                        .toList();
        if (forms.isEmpty()) {
            // The key is present but names no form. The repo schema requires `form`, so this is a
            // malformed document rather than a legal shape; describing a slot whose syntax is entirely
            // unknown would put a placeholder in the prompt with nothing to say about it.
            return Optional.empty();
        }
        return Optional.of(new IdentifierSlot(
                PresenceForm.PRESENCE_REQUIRED.equals(identifier.presence()), forms));
    }
}
