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

package io.ballerina.modelgenerator.commons.trigger.validation;

import io.ballerina.modelgenerator.commons.trigger.models.TriggerMetadataModel;
import io.ballerina.modelgenerator.commons.trigger.models.TypeRef;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * <b>Spec §9 variant integrity</b>: that a declared binding says something a consumer can act on, and that
 * its variants are distinguishable from one another.
 *
 * <h2>What this check became</h2>
 *
 * <p>It used to verify that {@code params[].dataBinding} named a rule the top-level
 * {@code dataBindingRules[]} registry actually declared — a dangling-reference check. Spec §9 moved the
 * binding inline onto the parameter, so there is no id, no registry, and no reference that can dangle: that
 * whole class of defect is gone by construction. What replaced it is the invariant the inline form makes
 * possible to state and easy to get wrong.
 *
 * <h2>Overlapping variants</h2>
 *
 * <p>Spec §9 explains {@code excludes} by the case it exists for: an envelope such as
 * {@code AnydataConsumerRecord} is itself valid {@code anydata}, so without an exclusion the same declared
 * type would satisfy both the bare variant and the unoverridden instantiation of the included variant, and
 * "a generator would have no way to know which was meant". The spec's own words for the goal are that
 * "every declared type maps to exactly one variant".
 *
 * <p>That is checkable: two variants sharing a constraint, where one includes an envelope and the other is
 * bare, are ambiguous unless the bare one excludes that envelope. Reported as an ERROR because the failure
 * is silent — the document is well-formed, and the consumer simply picks one reading.
 *
 * @since 1.10.0
 */
final class DataBindingRefCheck implements DocumentCheck {

    @Override
    public String id() {
        return "dataBinding";
    }

    @Override
    public String specSection() {
        return "§9";
    }

    @Override
    public List<Finding> check(TriggerMetadataModel document) {
        List<Finding> findings = new ArrayList<>();
        BindingWalk.forEachBinding(document, (binding, path) -> {
            List<TriggerMetadataModel.TypedescVariant> variants = DocumentWalk.safe(binding.typedescs());
            if (variants.isEmpty()) {
                findings.add(Finding.error(this, path + ".typedescs",
                        "a binding with no variants states no projection at all; omit `dataBinding` "
                                + "instead"));
                return;
            }
            for (int i = 0; i < variants.size(); i++) {
                if (variants.get(i) != null && variants.get(i).constraint() == null) {
                    findings.add(Finding.error(this, path + ".typedescs[" + i + "].constraint",
                            "required: a variant with no upper bound constrains nothing"));
                }
            }
            checkVariantsAreDistinguishable(findings, variants, path);
        });
        return findings;
    }

    /**
     * Spec §9: "every declared type maps to exactly one variant". Two variants over the same bound are
     * ambiguous when one embeds an envelope and the other does not, unless the bare one excludes it.
     */
    private void checkVariantsAreDistinguishable(List<Finding> findings,
                                                 List<TriggerMetadataModel.TypedescVariant> variants,
                                                 String path) {
        for (int i = 0; i < variants.size(); i++) {
            TriggerMetadataModel.TypedescVariant bare = variants.get(i);
            if (bare == null || bare.constraint() == null || embeddedEnvelopes(bare).isEmpty()) {
                continue;
            }
            // `bare` here is the variant that DOES embed envelopes; look for a sibling over the same bound
            // that does not, and check that sibling excludes every envelope this one embeds.
            for (int j = 0; j < variants.size(); j++) {
                TriggerMetadataModel.TypedescVariant other = variants.get(j);
                if (i == j || other == null || other.constraint() == null
                        || !sameBound(bare.constraint(), other.constraint())
                        || !embeddedEnvelopes(other).isEmpty()) {
                    continue;
                }
                Set<String> excluded = new LinkedHashSet<>();
                for (TypeRef ref : DocumentWalk.safe(other.excludes())) {
                    if (ref != null && ref.name() != null) {
                        excluded.add(ref.name());
                    }
                }
                for (String envelope : embeddedEnvelopes(bare)) {
                    if (!excluded.contains(envelope)) {
                        findings.add(Finding.error(this, path + ".typedescs[" + j + "].excludes",
                                "variant " + j + " and variant " + i + " share the bound `"
                                        + bare.constraint().name() + "`, and `" + envelope
                                        + "` satisfies both; spec §9 requires variant " + j
                                        + " to exclude it so every declared type maps to exactly one"
                                        + " variant"));
                    }
                }
            }
        }
    }

    /** The envelope names a variant's shapes embed, directly or as array/stream elements. */
    private Set<String> embeddedEnvelopes(TriggerMetadataModel.TypedescVariant variant) {
        Set<String> envelopes = new LinkedHashSet<>();
        for (TriggerMetadataModel.Shape shape : DocumentWalk.safe(variant.shapes())) {
            if (shape != null && shape.envelope() != null && shape.envelope().name() != null) {
                envelopes.add(shape.envelope().name());
            }
        }
        return envelopes;
    }

    private boolean sameBound(TypeRef one, TypeRef other) {
        return one.name() != null && one.name().equals(other.name());
    }
}
