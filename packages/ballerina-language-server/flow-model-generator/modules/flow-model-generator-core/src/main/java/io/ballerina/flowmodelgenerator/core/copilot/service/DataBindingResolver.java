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
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Owns <b>spec §9 {@code params[].dataBinding}</b> at the variant level: reading each {@code typedescs[]}
 * entry's bound and exclusions, and dispatching its {@code shapes[]} to {@link ShapeResolver}.
 *
 * <h2>What changed from the registry form</h2>
 *
 * <p>Spec v1.0 moved the binding <b>inline onto the parameter</b> and deleted the top-level
 * {@code dataBindingRules[]} registry, which removes an entire failure mode rather than relocating it:
 * there is no id, so there is no dangling reference to resolve, report, or drop a parameter over. What used
 * to be this component's most common diagnostic — "no {@code dataBindingRules[]} entry declares this id" —
 * cannot occur.
 *
 * <p>It also changed the unit of variation. The old {@code supportedModes[]} were alternative <i>modes</i>
 * over one shared rule, with {@code cardinality} hoisted to the rule so every mode was batched or none
 * was. The new {@code typedescs[]} are independent <i>variants</i>, each with its own bound, its own
 * exclusions and its own shapes — so one variant can be batched while its sibling is not, and two variants
 * may share a bound and differ only in shape. Nothing about the old shape could express that; kafka's
 * "array of bare values or array of envelope-including records" was approximated by a rule-level flag.
 *
 * <p><b>Degradation.</b> Nothing here throws. A variant naming no bound, or one whose every shape is
 * unreadable, is skipped; a binding whose every variant is skipped yields {@link Optional#empty()} so the
 * caller can report it against the parameter that declared it.
 *
 * @since 1.7.0
 */
final class DataBindingResolver {

    private DataBindingResolver() {
        // Prevent instantiation
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
    record Variant(String constraint, List<String> excludes, List<ShapeResolver.ResolvedShape> shapes) {
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
     * <p>{@code envelopeFields} is a plain lookup function rather than a facts object on purpose: it is the
     * only introspected input §9 needs (spec §9: "No {@code fixedFields}, they are the envelope's fields
     * minus {@code bindableFields}"), and taking it as a function keeps this resolver and
     * {@link ShapeResolver} unit-testable without a compiled package behind them.
     *
     * @param binding        the parameter's {@code dataBinding}; may be {@code null}
     * @param packageName    the resolved package name, for rendering type references per spec §1
     * @param declaresType   whether the home module declares a type of a given name
     * @param envelopeFields the declared field names of a record, by bare type name
     * @return the resolved binding, or empty when it states nothing a consumer can act on
     */
    static Optional<BindingSpec> resolve(TriggerMetadataModel.DataBinding binding, String packageName,
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
            List<ShapeResolver.ResolvedShape> shapes = new ArrayList<>();
            for (TriggerMetadataModel.Shape shape : safeShapes(variant)) {
                ShapeResolver.ResolvedShape resolved =
                        ShapeResolver.resolve(shape, packageName, declaresType, envelopeFields);
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

    private static String render(TypeRef ref, String packageName, Predicate<String> declaresType) {
        if (ref == null) {
            return null;
        }
        String rendered = TypeRefResolver.render(ref, packageName, declaresType);
        return rendered == null || rendered.isBlank() ? null : rendered;
    }
}
