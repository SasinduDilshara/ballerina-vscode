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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Owns <b>spec §5's resource extras</b>: the two positions of {@code resource function <accessor> <path>()}.
 *
 * <h2>Five components became one, and an inference disappeared</h2>
 *
 * <p>This replaces {@code HttpResourceExtrasResolver}, {@code GraphqlResourceExtrasResolver} and
 * {@code AccessorPrecedencePolicy}. The first two existed because the schema named the same two syntactic
 * slots differently per protocol — HTTP's {@code method}/{@code path} against GraphQL's
 * {@code accessor}/{@code fieldName} — plus an informational {@code graphqlOperation}.
 *
 * <p>The third existed for a worse reason. The spec did not say <i>which</i> of those keys supplied the
 * accessor a generator must write, and the three documents declaring one disagreed, so the pipeline carried
 * an explicitly-flagged guess: {@code accessor.values[0]} → {@code method.values[0]} → the handler's own
 * name. Spec §5 removed the question by giving the construct one {@code accessor} slot and stating the rule
 * symmetrically: "Both are required for {@code kind: "resource"} and neither applies to {@code kind:
 * "remote"}." There is nothing left to infer, so the inference — and the recommendation to raise it with the
 * spec author — is deleted rather than reduced.
 *
 * <p>{@code graphqlOperation} went too, and is not lost: it is derivable from what remains. A query is
 * {@code resource} with accessor {@code get}, a subscription is {@code resource} with accessor
 * {@code subscribe}, and a mutation is {@code remote}.
 *
 * @since 1.10.0
 */
final class ResourceExtrasResolver {

    private ResourceExtrasResolver() {
        // Prevent instantiation
    }

    /**
     * The resolved accessor and path of one resource handler.
     *
     * <h2>Both halves carry a vocabulary, because both slots are the same {@code valueSpec}</h2>
     *
     * <p>Spec §5 gives {@code accessor} and {@code path} the same {@code $ref}, so a {@code path} may
     * enumerate literal values or declare itself open ({@code values: ["*"]}) exactly as an accessor may.
     * Only the accessor half was carried, so a document constraining its path to a fixed vocabulary lost it
     * silently — and being an unexercised half of a shared definition is what made that invisible: no corpus
     * document sets {@code path.values}, so nothing rendered wrong, and nothing rendered at all.
     *
     * @param accessor         the accessor to write into the signature — the first declared value, per
     *                         spec §1's "first element is the codegen default". {@code null} when the
     *                         document leaves it open
     * @param accessorValues   every legal accessor, for the note; empty when the slot is open
     * @param accessorRequired whether an accessor must be written
     * @param accessorOpen     spec §5's {@code values: ["*"]} — any accessor the language accepts. Told
     *                         apart from an enumerated list because a note saying "must be one of `*`" is
     *                         nonsense, whereas "any accessor the language accepts" is usable
     * @param path             the path to write, when the document names exactly the vocabulary to choose
     *                         from; {@code null} when it enumerates none, which is every corpus document and
     *                         means the path is the author's to compose
     * @param pathValues       every legal path, for the note; empty when the document enumerates none
     * @param pathRequired     whether a path must be written. There is still no syntactic {@code form}: spec
     *                         §5 dropped that vocabulary because "the language already fixes what a resource
     *                         path may look like". A value list is a different claim — it names the specific
     *                         paths this connector accepts, which the language cannot know
     * @param pathOpen         spec §5's {@code values: ["*"]} at the path slot
     */
    record ResourceExtras(String accessor, List<String> accessorValues, boolean accessorRequired,
                          boolean accessorOpen, String path, List<String> pathValues, boolean pathRequired,
                          boolean pathOpen) {
    }

    /**
     * Resolves a handler's resource extras.
     *
     * @param option the handler option
     * @return the extras, or empty for a handler that declares neither slot — which for a {@code remote}
     *         handler is the correct and expected state
     */
    static Optional<ResourceExtras> resolve(TriggerMetadataModel.ServiceType.HandlerOption option) {
        if (option == null) {
            return Optional.empty();
        }
        ValueSpec accessor = option.accessor();
        ValueSpec path = option.path();
        if (accessor == null && path == null) {
            return Optional.empty();
        }
        Slot resolvedAccessor = slot(accessor);
        Slot resolvedPath = slot(path);
        return Optional.of(new ResourceExtras(
                resolvedAccessor.chosen(), resolvedAccessor.values(), resolvedAccessor.required(),
                resolvedAccessor.open(),
                resolvedPath.chosen(), resolvedPath.values(), resolvedPath.required(),
                resolvedPath.open()));
    }

    /**
     * One {@code valueSpec} slot, resolved.
     *
     * <p>Shared by both halves rather than duplicated, which is the point: they are the same definition in
     * the schema, so reading them differently is how one of them came to be half-implemented.
     *
     * @param chosen   the value to write — the first declared one, per spec §1's codegen-default rule;
     *                 {@code null} when the slot is open or enumerates nothing
     * @param values   every legal value; empty when the slot is open or enumerates nothing
     * @param required whether the slot must be written
     * @param open     whether the document declares {@code values: ["*"]}
     */
    private record Slot(String chosen, List<String> values, boolean required, boolean open) {
    }

    private static Slot slot(ValueSpec spec) {
        List<String> values = values(spec);
        boolean open = spec != null && spec.isOpen();
        // An open slot has no single default to write; the renderer emits a placeholder and the note says
        // the value is the author's to choose.
        String chosen = open || values.isEmpty() ? null : values.get(0);
        return new Slot(chosen, open ? List.of() : values, spec != null && spec.isRequired(), open);
    }

    private static List<String> values(ValueSpec spec) {
        if (spec == null || spec.values() == null) {
            return List.of();
        }
        List<String> kept = new ArrayList<>();
        for (String value : spec.values()) {
            if (value != null && !value.isBlank()) {
                kept.add(value);
            }
        }
        return kept;
    }
}
