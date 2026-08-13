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

import java.util.ArrayList;
import java.util.List;

/**
 * <b>Spec §5.1 {@code handlers.options[].addMode}</b> and the wildcard it pairs with.
 *
 * <h2>What spec §5.1 fixed, and why this check shrank</h2>
 *
 * <p>{@code addMode} used to sit on the {@code handlers} block, which forced a service type to be entirely
 * fixed-name or entirely open-ended. Three corpus documents could not be said in that vocabulary, and this
 * check existed largely to report the resulting damage:
 *
 * <ul>
 *   <li><b>{@code grpc}</b> declared {@code many} with four <i>named</i> options and no wildcard, so the
 *       consumer read them as a fixed vocabulary and had to add a separate "these are shapes, not names"
 *       note to stop four labels that appear in no real program reading like {@code salesforce}'s genuinely
 *       fixed {@code onCreate}.</li>
 *   <li><b>{@code graphql}</b> declared three {@code "*"} entries where the block-level reading allowed
 *       one, and two thirds of its handler surface — the mutation and the subscription — were dropped.</li>
 *   <li>A service type offering fixed lifecycle handlers <i>alongside</i> open user-named ones could not be
 *       expressed at all.</li>
 * </ul>
 *
 * <p>Spec §5.1 moved the flag onto each option and says so outright: "One service type may carry several
 * {@code "*"} options when it offers several distinct shapes. gRPC has four, one per RPC kind, and GraphQL
 * has three, one per operation." All three situations are now simply legal, so the warnings that reported
 * them are gone rather than downgraded. What remains is the small set of things that are still genuinely
 * contradictory.
 *
 * @since 1.10.0
 */
final class AddModeCheck implements DocumentCheck {

    private static final String WILDCARD = TriggerMetadataModel.ServiceType.HandlerOption.WILDCARD_NAME;

    @Override
    public String id() {
        return "addMode";
    }

    @Override
    public String specSection() {
        // §4 for the block-level invariants (backedByConcreteType vs options) it still owns; the
        // option-level addMode rules it also checks are §5.1.
        return "§4";
    }

    @Override
    public List<Finding> check(TriggerMetadataModel document) {
        List<Finding> findings = new ArrayList<>();
        List<TriggerMetadataModel.ServiceType> serviceTypes = DocumentWalk.safe(document.serviceTypes());
        for (int i = 0; i < serviceTypes.size(); i++) {
            TriggerMetadataModel.ServiceType serviceType = serviceTypes.get(i);
            if (serviceType == null || serviceType.handlers() == null) {
                continue;
            }
            TriggerMetadataModel.ServiceType.Handlers handlers = serviceType.handlers();
            String path = DocumentWalk.serviceTypePath(i) + ".handlers";
            List<TriggerMetadataModel.ServiceType.HandlerOption> options = DocumentWalk.options(serviceType);

            if (handlers.backedByConcreteType()) {
                // Spec §4: "A concrete backed type says nothing further, so `options` is omitted too."
                if (!options.isEmpty()) {
                    findings.add(Finding.error(this, path,
                            "backedByConcreteType is true, so the type's own methods are the handlers; "
                                    + options.size() + " option(s) here can never be read"));
                }
                continue;
            }
            if (options.isEmpty()) {
                findings.add(Finding.error(this, path + ".options",
                        "required when backedByConcreteType is false; options are the only source of truth"));
                continue;
            }

            for (int j = 0; j < options.size(); j++) {
                TriggerMetadataModel.ServiceType.HandlerOption option = options.get(j);
                if (option == null) {
                    continue;
                }
                checkOption(findings, option, DocumentWalk.optionPath(i, j));
            }
        }
        return findings;
    }

    private void checkOption(List<Finding> findings,
                             TriggerMetadataModel.ServiceType.HandlerOption option, String path) {
        boolean wildcard = WILDCARD.equals(option.name());
        String addMode = option.addMode();

        if (addMode != null
                && !TriggerMetadataModel.ServiceType.HandlerOption.ADD_MODE_SUBSET.equals(addMode)
                && !TriggerMetadataModel.ServiceType.HandlerOption.ADD_MODE_MANY.equals(addMode)) {
            findings.add(Finding.error(this, path + ".addMode",
                    "'" + addMode + "'; spec §5.1 defines only 'subset' and 'many'"));
            return;
        }

        if (option.isMany()) {
            // Spec §5.1: "A `many` option is always named `\"*\"`. The user picks the real name, so there
            // is none to record." A real name here would be rendered as a literal handler nobody writes.
            if (!wildcard) {
                findings.add(Finding.error(this, path + ".name",
                        "'" + option.name() + "' under addMode \"many\"; the user names each instance, so"
                                + " the name must be \"*\""));
            }
            // Spec §5.1: "A `many` shape has no fixed occurrence count to require."
            if (option.presence() != null) {
                findings.add(Finding.error(this, path + ".presence",
                        "stated under addMode \"many\", which has no fixed occurrence count to require"));
            }
            return;
        }

        // Absent addMode reads as `subset`, where the name is the method to emit -- and "*" is not one.
        if (wildcard) {
            findings.add(Finding.error(this, path + ".name",
                    "\"*\" under addMode \"" + (addMode == null ? "subset\" (the reading when absent)"
                            : addMode + "\"") + "; spec §5.1 pairs the wildcard with \"many\""));
        }
    }
}
