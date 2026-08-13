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
import io.ballerina.modelgenerator.commons.trigger.utils.TypeRefResolver;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Owns <b>spec §9's {@code shapes[]} table</b>: how one {@code typedescs[]} variant's bound type is
 * embedded in the declared parameter type.
 *
 * <h2>One component for four forms, where there used to be three for three modes</h2>
 *
 * <p>Spec v1.0 replaced the {@code direct}/{@code includedRecord}/{@code streamable} modes with the
 * {@code bare}/{@code array}/{@code stream}/{@code included} forms, and in doing so changed what the unit
 * of variation <i>is</i>. Under the old modes each carried a different set of fields, which is why each got
 * its own resolver. Under the new forms three of the four carry nothing but wrapping — {@code bare} has no
 * fields at all — and the only substantive branch is whether the shape embeds an envelope. Splitting that
 * across four files would put three empty resolvers next to one real one, so §9's shape table gets a single
 * owner instead.
 *
 * <p>What the old {@code IncludedRecordModeResolver} genuinely owned <b>does</b> survive here intact: the
 * derivation of {@code fixedFields} as "the envelope's declared fields minus {@code bindableFields}", which
 * spec §9 requires be derived rather than restated.
 *
 * <h2>Included elements are the case the schema misses</h2>
 *
 * <p>An envelope is embedded not only by {@code form: "included"} but also by an {@code array} or
 * {@code stream} whose {@code element} is {@code included} — which is the corpus's actual batched-envelope
 * shape (kafka's). The published {@code spec.json} requires {@code envelope}/{@code bindableFields} only
 * for the former, so this component reads both and {@code BindingModeCheck} reports a shape that claims an
 * included element without naming what it includes.
 *
 * @since 1.10.0
 */
final class ShapeResolver {

    private ShapeResolver() {
        // Prevent instantiation
    }

    /**
     * One resolved embedding of a variant's bound type.
     *
     * @param form           spec §9's {@code form}, carried verbatim so the renderer decides the wording
     * @param element        for {@code array}/{@code stream}, whether each item is bare or included;
     *                       {@code null} otherwise
     * @param envelope       the record a user type includes with {@code *Envelope;}, as module-prefixed
     *                       signature text; {@code null} for a shape that embeds none
     * @param bindableFields the envelope's fields this variant may retype, in document order; never
     *                       truncated
     * @param fixedFields    the envelope's remaining fields, derived rather than restated (spec §9). Empty
     *                       when the envelope is not an introspectable record of the resolved package — in
     *                       which case a consumer must not claim to know which fields are pinned
     * @param completionType for {@code stream}, the stream's completion type as signature text;
     *                       {@code null} otherwise
     */
    record ResolvedShape(String form,
                         String element,
                         String envelope,
                         List<String> bindableFields,
                         List<String> fixedFields,
                         String completionType) {

        /** Whether this shape embeds an envelope, whether directly or per element. */
        boolean embedsEnvelope() {
            return TriggerMetadataModel.Shape.FORM_INCLUDED.equals(form)
                    || TriggerMetadataModel.Shape.FORM_INCLUDED.equals(element);
        }

        /** Whether the declared type is a batch of this shape's bound type. */
        boolean isBatched() {
            return TriggerMetadataModel.Shape.FORM_ARRAY.equals(form)
                    || TriggerMetadataModel.Shape.FORM_STREAM.equals(form);
        }
    }

    /**
     * Resolves one shape.
     *
     * @param shape          the {@code shapes[]} entry
     * @param packageName    the resolved package name, for rendering type references per spec §1
     * @param declaresType   whether the home module declares a type of a given name
     * @param envelopeFields the declared field names of a record, by bare type name
     * @return the resolved shape, or {@code null} when the entry names no form and so states nothing
     */
    static ResolvedShape resolve(TriggerMetadataModel.Shape shape, String packageName,
                                 Predicate<String> declaresType,
                                 Function<String, List<String>> envelopeFields) {
        if (shape == null || shape.form() == null || shape.form().isBlank()) {
            return null;
        }
        String envelope = render(shape.envelope(), packageName, declaresType);
        List<String> bindable = nonBlank(shape.bindableFields());
        return new ResolvedShape(
                shape.form(),
                blankToNull(shape.element()),
                envelope,
                bindable,
                fixedFields(shape.envelope(), bindable, envelopeFields),
                renderUnion(shape.completionType(), packageName, declaresType));
    }

    /**
     * Spec §9's derivation: the envelope's declared fields minus the bindable ones, in declaration order.
     *
     * <p>Uses the envelope's <b>bare</b> name, not its rendered signature: the lookup is against the
     * resolved package's own symbols, where a type is known by the name it was declared with.
     */
    private static List<String> fixedFields(TypeRef envelope, List<String> bindableFields,
                                            Function<String, List<String>> envelopeFields) {
        if (envelope == null || envelope.name() == null || envelopeFields == null) {
            return List.of();
        }
        List<String> declared = envelopeFields.apply(TypeRefResolver.baseIdentifier(envelope.name()));
        if (declared == null || declared.isEmpty()) {
            return List.of();
        }
        Set<String> bindable = new LinkedHashSet<>(bindableFields);
        List<String> fixed = new ArrayList<>();
        for (String field : declared) {
            if (!bindable.contains(field)) {
                fixed.add(field);
            }
        }
        return fixed;
    }

    private static List<String> nonBlank(List<String> values) {
        if (values == null) {
            return List.of();
        }
        List<String> kept = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                kept.add(value);
            }
        }
        return kept;
    }

    private static String render(TypeRef ref, String packageName, Predicate<String> declaresType) {
        return ref == null ? null : blankToNull(TypeRefResolver.render(ref, packageName, declaresType));
    }

    /**
     * A completion type, which spec §9 types as a TypeRef-or-union so that a nilable one is expressed the
     * same way as everywhere else — an explicit {@code ()} member rather than a flag.
     */
    private static String renderUnion(List<TypeRef> refs, String packageName,
                                      Predicate<String> declaresType) {
        return refs == null || refs.isEmpty() ? null
                : blankToNull(TypeRefResolver.renderNilableUnion(refs, packageName, declaresType));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
