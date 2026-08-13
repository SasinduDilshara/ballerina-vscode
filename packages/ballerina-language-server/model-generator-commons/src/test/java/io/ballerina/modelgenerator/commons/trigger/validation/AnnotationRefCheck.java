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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * <b>Spec §8 registry integrity</b>: every {@code annotations[]} id is unique, every reference to one
 * resolves, every reference sits at the attach point its entry declares, and every declared entry is
 * reachable from somewhere.
 *
 * <p>This is the class of defect a JSON schema cannot catch, which is why the validator tier exists at
 * all: {@code "annotations": ["$payload"]} is a perfectly well-formed array of strings whether or not any
 * registry entry declares {@code $payload}.
 *
 * <h2>Reachability, which is new and load-bearing</h2>
 *
 * <p>Spec §8 replaced the reverse {@code appliesTo} list with a forward reference from the construct that
 * carries the annotation, and states that "every annotation in the corpus is now reachable by forward
 * reference". That turns an unreferenced entry from a stylistic wart into a <b>silent loss</b>: with no
 * reverse list and no fallback, an annotation nothing points at attaches nowhere, and its obligation
 * reaches the prompt as nothing.
 *
 * <p>The failure is not hypothetical. Two documents in the published corpus — {@code smb} and
 * {@code rabbitmq} — declare a service-scope {@code $serviceConfig} and reference it only from a rule
 * subject, never from {@code serviceTypes[].annotations}. {@code smb}'s is {@code presence: "required"},
 * so a literal reading emits a service that is missing a mandatory annotation. Reported here as an ERROR
 * so the document is fixed rather than the consumer growing an implicit second way to attach.
 *
 * <p>A rule subject naming an annotation is deliberately <b>not</b> counted as a reference for this
 * purpose: §8's table maps each attach point to exactly one referencing field, and a rule says what an
 * annotation <i>relates to</i>, not where it attaches.
 *
 * @since 1.10.0
 */
final class AnnotationRefCheck implements DocumentCheck {

    @Override
    public String id() {
        return "annotationRef";
    }

    @Override
    public String specSection() {
        return "§8";
    }

    @Override
    public List<Finding> check(TriggerMetadataModel document) {
        List<Finding> findings = new ArrayList<>();
        Set<String> declared = new LinkedHashSet<>();
        Set<String> duplicates = new LinkedHashSet<>();

        for (TriggerMetadataModel.Annotation annotation : DocumentWalk.safe(document.annotations())) {
            if (annotation == null) {
                continue;
            }
            if (annotation.id() == null || annotation.id().isBlank()) {
                findings.add(Finding.error(this, "annotations[]",
                        "an entry declares no `id`, so nothing can reference it"));
                continue;
            }
            if (!declared.add(annotation.id()) && duplicates.add(annotation.id())) {
                findings.add(Finding.error(this, "annotations[" + annotation.id() + "]",
                        "duplicate id: a reference to it is ambiguous"));
            }
        }

        // Every id referenced from a construct that carries an annotation, with the attach point that
        // reference site implies. Collected while walking so the reachability check below needs no second
        // pass.
        Set<String> referenced = new LinkedHashSet<>();

        List<TriggerMetadataModel.ServiceType> serviceTypes = DocumentWalk.safe(document.serviceTypes());
        for (int i = 0; i < serviceTypes.size(); i++) {
            TriggerMetadataModel.ServiceType serviceType = serviceTypes.get(i);
            if (serviceType == null) {
                continue;
            }
            String servicePath = DocumentWalk.serviceTypePath(i);
            checkRefs(findings, referenced, serviceType.annotations(), declared, document,
                    TriggerMetadataModel.Annotation.ATTACH_POINT_SERVICE, servicePath + ".annotations");

            List<TriggerMetadataModel.ServiceType.HandlerOption> options = DocumentWalk.options(serviceType);
            for (int j = 0; j < options.size(); j++) {
                TriggerMetadataModel.ServiceType.HandlerOption option = options.get(j);
                if (option == null) {
                    continue;
                }
                String optionPath = DocumentWalk.optionPath(i, j);
                checkRefs(findings, referenced, option.annotations(), declared, document,
                        TriggerMetadataModel.Annotation.ATTACH_POINT_FUNCTION, optionPath + ".annotations");
                checkRefs(findings, referenced, option.returnAnnotations(), declared, document,
                        TriggerMetadataModel.Annotation.ATTACH_POINT_RETURN,
                        optionPath + ".returnAnnotations");

                List<TriggerMetadataModel.ServiceType.Param> params = DocumentWalk.safe(option.params());
                for (int k = 0; k < params.size(); k++) {
                    if (params.get(k) != null) {
                        checkRefs(findings, referenced, params.get(k).annotations(), declared, document,
                                TriggerMetadataModel.Annotation.ATTACH_POINT_PARAMETER,
                                DocumentWalk.paramPath(i, j, k) + ".annotations");
                    }
                }
            }
        }

        for (TriggerMetadataModel.Annotation annotation : DocumentWalk.safe(document.annotations())) {
            if (annotation == null || annotation.id() == null || referenced.contains(annotation.id())) {
                continue;
            }
            findings.add(Finding.error(this, "annotations[" + annotation.id() + "]",
                    "declared but never referenced from " + referencingField(annotation.attachPoint())
                            + "; spec §8 reaches every annotation by forward reference, so an unreferenced"
                            + " entry attaches nowhere and its obligation reaches a consumer as nothing"));
        }
        return findings;
    }

    /**
     * Checks one reference list: that every id resolves, and that the entry it resolves to declares the
     * attach point this reference site implies.
     */
    private void checkRefs(List<Finding> findings, Set<String> referenced, List<String> refs,
                           Set<String> declared, TriggerMetadataModel document, String expectedAttachPoint,
                           String path) {
        for (String ref : DocumentWalk.safe(refs)) {
            if (ref == null || ref.isBlank()) {
                findings.add(Finding.error(this, path, "a blank annotation reference names nothing"));
                continue;
            }
            referenced.add(ref);
            if (!declared.contains(ref)) {
                findings.add(Finding.error(this, path,
                        "references annotation id '" + ref
                                + "', which no annotations[] entry declares"));
                continue;
            }
            String actual = attachPointOf(document, ref);
            if (actual != null && !expectedAttachPoint.equals(actual)) {
                findings.add(Finding.error(this, path,
                        "references '" + ref + "', whose attachPoint is '" + actual + "'; "
                                + referencingField(expectedAttachPoint)
                                + " may only reference an annotation with attachPoint '"
                                + expectedAttachPoint + "'"));
            }
        }
    }

    private String attachPointOf(TriggerMetadataModel document, String id) {
        for (TriggerMetadataModel.Annotation annotation : DocumentWalk.safe(document.annotations())) {
            if (annotation != null && id.equals(annotation.id())) {
                return annotation.attachPoint();
            }
        }
        return null;
    }

    /** Spec §8's attach-point-to-referencing-field table, as prose for a diagnostic. */
    private String referencingField(String attachPoint) {
        if (attachPoint == null) {
            return "any reference site";
        }
        return switch (attachPoint) {
            case TriggerMetadataModel.Annotation.ATTACH_POINT_SERVICE -> "`serviceTypes[].annotations`";
            case TriggerMetadataModel.Annotation.ATTACH_POINT_FUNCTION ->
                    "`handlers.options[].annotations`";
            case TriggerMetadataModel.Annotation.ATTACH_POINT_RETURN ->
                    "`handlers.options[].returnAnnotations`";
            case TriggerMetadataModel.Annotation.ATTACH_POINT_PARAMETER -> "`params[].annotations`";
            default -> "any reference site";
        };
    }
}
