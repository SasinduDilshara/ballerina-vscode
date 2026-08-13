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

import com.google.gson.JsonObject;
import io.ballerina.modelgenerator.commons.trigger.models.TriggerMetadataModel;
import io.ballerina.modelgenerator.commons.trigger.models.TypeRef;
import io.ballerina.modelgenerator.commons.trigger.utils.TypeRefResolver;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Type-shape resolution: a handler return, a data-binding shape, and the binding spec built from them.
 * Grouped because all three turn document type references into rendered signatures the same way.
 *
 * @since 1.7.0
 */
final class TypeShapeRules {


    private static final String NIL = "()";

    private TypeShapeRules() {
        // Prevent instantiation
    }

    /** The union's joined, module-prefixed signature text. */
    static String signature(List<TypeRef> returns, String packageName, Predicate<String> declaresType) {
        return TypeRefResolver.renderUnion(returns, packageName, declaresType);
    }

    /**
     * Builds the {@code return} object from an already-joined signature.
     *
     * @param returnSignature the joined signature, or the declared method's own return signature
     * @param packageName     the resolved package name, for link resolution
     * @return the {@code {type: {...}}} object, or empty when the return carries no information
     */
    static Optional<JsonObject> resolveReturn(String returnSignature, String packageName) {
        if (returnSignature == null || returnSignature.isEmpty()) {
            return Optional.empty();
        }
        String canonical = ServiceIndexLoader.canonicalizeReturnType(returnSignature);
        if (canonical.isEmpty() || NIL.equals(canonical)) {
            return Optional.empty();
        }
        JsonObject returnObj = new JsonObject();
        returnObj.add("type", TypeResolver.resolveTypeWithLinks(canonical, packageName));
        return Optional.of(returnObj);
    }

    /**
     * One resolved embedding of a variant's bound type.
     *
     * @param form           the spec's {@code form}, carried verbatim so the renderer decides the wording
     * @param element        for {@code array}/{@code stream}, whether each item is bare or included;
     *                       {@code null} otherwise
     * @param envelope       the record a user type includes with {@code *Envelope;}, as module-prefixed
     *                       signature text; {@code null} for a shape that embeds none
     * @param bindableFields the envelope's fields this variant may retype, in document order; never
     *                       truncated
     * @param fixedFields    the envelope's remaining fields, derived rather than restated (the spec). Empty
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
    }

    /**
     * Resolves one shape.
     *
     * @param shape          the {@code shapes[]} entry
     * @param packageName    the resolved package name, for rendering type references per the spec
     * @param declaresType   whether the home module declares a type of a given name
     * @param envelopeFields the declared field names of a record, by bare type name
     * @return the resolved shape, or {@code null} when the entry names no form and so states nothing
     */
    static ResolvedShape resolveShape(TriggerMetadataModel.Shape shape, String packageName,
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
     * The spec's derivation: the envelope's declared fields minus the bindable ones, in declaration order.
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
     * A completion type, which the spec types as a TypeRef-or-union so that a nilable one is expressed the
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

    /**
     * One resolved {@code typedescs[]} variant.
     *
     * @param constraint this variant's upper bound, as module-prefixed signature text
     * @param excludes   instantiations a sibling variant owns, which this one must not claim. A negative
     *                   constraint, derivable from nothing else, so a consumer states it even when every
     *                   positive type is already visible
     * @param shapes     the legal embeddings of this variant's bound, in document order; never empty
     */
    record Variant(String constraint, List<String> excludes, List<ResolvedShape> shapes) {
    }

    /**
     * A resolved {@code params[].dataBinding}.
     *
     * @param variants the independent variants, in document order; never empty
     */
    record BindingSpec(List<Variant> variants) {
    }

    /**
     * Resolves a parameter's inline binding.
     *
     * @param binding        the parameter's {@code dataBinding}; may be {@code null}
     * @param packageName    the resolved package name, for rendering type references per the spec
     * @param declaresType   whether the home module declares a type of a given name
     * @param envelopeFields the declared field names of a record, by bare type name
     * @return the resolved binding, or empty when it states nothing a consumer can act on
     */
    static Optional<BindingSpec> resolveBinding(TriggerMetadataModel.DataBinding binding, String packageName,
                                         Predicate<String> declaresType,
                                         Function<String, List<String>> envelopeFields) {
        if (binding == null || binding.typedescs() == null || binding.typedescs().isEmpty()) {
            return Optional.empty();
        }
        List<Variant> variants = new ArrayList<>();
        for (TriggerMetadataModel.TypedescVariant variant : binding.typedescs()) {
            if (variant == null) {
                continue;
            }
            String constraint = render(variant.constraint(), packageName, declaresType);
            if (constraint == null) {
                // A variant with no bound constrains nothing, so there is no type for a consumer to offer.
                continue;
            }
            List<ResolvedShape> shapes = new ArrayList<>();
            for (TriggerMetadataModel.Shape shape : safeShapes(variant)) {
                ResolvedShape resolved =
                        resolveShape(shape, packageName, declaresType, envelopeFields);
                if (resolved != null) {
                    shapes.add(resolved);
                }
            }
            if (shapes.isEmpty()) {
                // The bound is known but no way of embedding it is, which describes no declarable type.
                continue;
            }
            variants.add(new Variant(constraint, renderAll(variant.excludes(), packageName, declaresType),
                    List.copyOf(shapes)));
        }
        return variants.isEmpty() ? Optional.empty() : Optional.of(new BindingSpec(List.copyOf(variants)));
    }

    private static List<TriggerMetadataModel.Shape> safeShapes(TriggerMetadataModel.TypedescVariant variant) {
        return variant.shapes() == null ? List.of() : variant.shapes();
    }

    private static List<String> renderAll(List<TypeRef> refs, String packageName,
                                          Predicate<String> declaresType) {
        if (refs == null || refs.isEmpty()) {
            return List.of();
        }
        List<String> rendered = new ArrayList<>();
        for (TypeRef ref : refs) {
            String value = render(ref, packageName, declaresType);
            if (value != null) {
                rendered.add(value);
            }
        }
        return rendered;
    }

}
