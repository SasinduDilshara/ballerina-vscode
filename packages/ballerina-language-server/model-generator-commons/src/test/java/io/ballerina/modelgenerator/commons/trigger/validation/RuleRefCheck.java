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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>Spec §6 {@code rules[]} integrity</b>: subject shapes, subject count, the roles a rule's registry
 * entry demands, and the handler/annotation names its subjects refer to.
 *
 * <h2>Validating an open registry</h2>
 *
 * <p>Spec §6 makes the rule vocabulary open and requires a <i>consumer</i> to skip an id it does not
 * recognise. That is the right runtime behaviour and exactly the wrong validation behaviour: over this
 * repo's own corpus, an unrecognised id is far more likely to be a typo than a constraint from the future,
 * and skipping it silently is how {@code structure.exactlyOne} misspelt as {@code structure.exactlyone}
 * would reach the prompt as no constraint at all. So the runtime skips and this reports.
 *
 * <p>Reported as a WARN rather than an ERROR, because "an id this build does not implement" is a legitimate
 * state under §11.3 — a document may target a later minor. The distinction a reader needs is that the rule
 * will have no effect here, which is what the finding says.
 *
 * <h2>Asymmetric constraints</h2>
 *
 * <p>{@code structure.requires} and {@code structure.conflictsWith} are the two entries whose subjects are
 * <b>not</b> interchangeable: §6 fixes their roles as {@code when} and {@code then}. A document that omits
 * the roles leaves a consumer to guess which subject is the antecedent, and guessing wrong inverts the
 * constraint, so the roles are required for exactly those two.
 *
 * @since 1.10.0
 */
final class RuleRefCheck implements DocumentCheck {

    /** The registry entries this build implements, and how many subjects each needs. */
    private static final Map<String, Integer> MIN_SUBJECTS = new LinkedHashMap<>();

    /** The entries whose subjects are asymmetric and must therefore be role-labelled. */
    private static final Set<String> ASYMMETRIC = Set.of(
            TriggerMetadataModel.Rule.RULE_REQUIRES,
            TriggerMetadataModel.Rule.RULE_CONFLICTS_WITH);

    static {
        MIN_SUBJECTS.put(TriggerMetadataModel.Rule.RULE_EXACTLY_ONE, 2);
        MIN_SUBJECTS.put(TriggerMetadataModel.Rule.RULE_AT_MOST_ONE, 2);
        MIN_SUBJECTS.put(TriggerMetadataModel.Rule.RULE_AT_LEAST_ONE, 2);
        MIN_SUBJECTS.put(TriggerMetadataModel.Rule.RULE_ALL_OR_NONE, 2);
        MIN_SUBJECTS.put(TriggerMetadataModel.Rule.RULE_REQUIRES, 2);
        MIN_SUBJECTS.put(TriggerMetadataModel.Rule.RULE_CONFLICTS_WITH, 2);
    }

    @Override
    public String id() {
        return "ruleRef";
    }

    @Override
    public String specSection() {
        return "§6";
    }

    @Override
    public List<Finding> check(TriggerMetadataModel document) {
        List<Finding> findings = new ArrayList<>();
        Set<String> annotationIds = new LinkedHashSet<>();
        for (TriggerMetadataModel.Annotation annotation : DocumentWalk.safe(document.annotations())) {
            if (annotation != null && annotation.id() != null) {
                annotationIds.add(annotation.id());
            }
        }

        Map<String, Set<String>> handlersByServiceType = new LinkedHashMap<>();
        List<TriggerMetadataModel.ServiceType> serviceTypes = DocumentWalk.safe(document.serviceTypes());
        for (TriggerMetadataModel.ServiceType serviceType : serviceTypes) {
            if (serviceType == null || serviceType.id() == null) {
                continue;
            }
            Set<String> names = new LinkedHashSet<>();
            for (TriggerMetadataModel.ServiceType.HandlerOption option : DocumentWalk.options(serviceType)) {
                if (option != null && option.name() != null) {
                    names.add(option.name());
                }
            }
            handlersByServiceType.put(serviceType.id(), names);
        }

        for (int i = 0; i < serviceTypes.size(); i++) {
            TriggerMetadataModel.ServiceType serviceType = serviceTypes.get(i);
            if (serviceType == null) {
                continue;
            }
            for (TriggerMetadataModel.Rule rule : DocumentWalk.safe(serviceType.rules())) {
                if (rule != null) {
                    checkRule(findings, rule, serviceType.id(), false, annotationIds, handlersByServiceType,
                            DocumentWalk.serviceTypePath(i) + ".rules[" + rule.id() + "]");
                }
            }
        }
        for (TriggerMetadataModel.Rule rule : DocumentWalk.safe(document.rules())) {
            if (rule != null) {
                checkRule(findings, rule, null, true, annotationIds, handlersByServiceType,
                        "rules[" + rule.id() + "]");
            }
        }
        return findings;
    }

    private void checkRule(List<Finding> findings, TriggerMetadataModel.Rule rule, String enclosingServiceType,
                           boolean topLevel, Set<String> annotationIds,
                           Map<String, Set<String>> handlersByServiceType, String path) {
        if (rule.id() == null || rule.id().isBlank()) {
            findings.add(Finding.error(this, path + ".id",
                    "required: the id surfaces in the emitted diagnostic"));
        }
        String registryId = rule.rule();
        if (registryId == null || registryId.isBlank()) {
            findings.add(Finding.error(this, path + ".rule",
                    "required: a rule is referenced from the registry, never defined inline"));
            return;
        }

        List<TriggerMetadataModel.Subject> subjects = DocumentWalk.safe(rule.subjects());
        if (!MIN_SUBJECTS.containsKey(registryId)) {
            findings.add(Finding.warn(this, path + ".rule",
                    "'" + registryId + "' is not a registry entry this build implements, so the rule is"
                            + " skipped and states nothing here; spec §6.2 lists the implemented entries"));
        } else if (subjects.size() < MIN_SUBJECTS.get(registryId)) {
            findings.add(Finding.error(this, path + ".subjects",
                    "'" + registryId + "' needs at least " + MIN_SUBJECTS.get(registryId)
                            + " subjects to constrain anything, found " + subjects.size()));
        }

        if (ASYMMETRIC.contains(registryId)) {
            checkRoles(findings, subjects, registryId, path);
        }
        // `reportOn` was removed in spec §6; `prefer` is the only role reference left.
        checkRoleReference(findings, rule.prefer(), subjects, path, "prefer");

        if (rule.severity() != null
                && !TriggerMetadataModel.Rule.SEVERITY_ERROR.equals(rule.severity())
                && !TriggerMetadataModel.Rule.SEVERITY_WARNING.equals(rule.severity())) {
            findings.add(Finding.error(this, path + ".severity",
                    "'" + rule.severity() + "'; spec §10 defines only 'error' and 'warning'"));
        }

        for (int i = 0; i < subjects.size(); i++) {
            checkSubject(findings, subjects.get(i), enclosingServiceType, topLevel, annotationIds,
                    handlersByServiceType, path + ".subjects[" + i + "]");
        }
    }

    private void checkRoles(List<Finding> findings, List<TriggerMetadataModel.Subject> subjects,
                            String registryId, String path) {
        boolean when = false;
        boolean then = false;
        for (TriggerMetadataModel.Subject subject : subjects) {
            if (subject == null) {
                continue;
            }
            when |= TriggerMetadataModel.Rule.ROLE_WHEN.equals(subject.role());
            then |= TriggerMetadataModel.Rule.ROLE_THEN.equals(subject.role());
        }
        if (!when || !then) {
            findings.add(Finding.error(this, path + ".subjects",
                    "'" + registryId + "' is asymmetric, so its subjects must carry the roles `"
                            + TriggerMetadataModel.Rule.ROLE_WHEN + "` and `"
                            + TriggerMetadataModel.Rule.ROLE_THEN
                            + "`; without them a consumer cannot tell which side is the antecedent, and"
                            + " guessing inverts the constraint"));
        }
    }

    private void checkRoleReference(List<Finding> findings, String role,
                                    List<TriggerMetadataModel.Subject> subjects, String path, String key) {
        if (role == null || role.isBlank()) {
            return;
        }
        for (TriggerMetadataModel.Subject subject : subjects) {
            if (subject != null && role.equals(subject.role())) {
                return;
            }
        }
        findings.add(Finding.error(this, path + "." + key,
                "names role '" + role + "', which no subject of this rule carries"));
    }

    private void checkSubject(List<Finding> findings, TriggerMetadataModel.Subject subject,
                              String enclosingServiceType, boolean topLevel, Set<String> annotationIds,
                              Map<String, Set<String>> handlersByServiceType, String path) {
        if (subject == null) {
            findings.add(Finding.error(this, path, "a null subject states nothing"));
            return;
        }
        String kind = subject.kind();
        if (kind == null || kind.isBlank()) {
            findings.add(Finding.error(this, path + ".kind",
                    "required: `kind` is the discriminator, so without it the subject shape is unreadable"));
            return;
        }

        String serviceTypeId = subject.serviceType() != null ? subject.serviceType() : enclosingServiceType;
        if (topLevel && subject.serviceType() == null) {
            findings.add(Finding.error(this, path + ".serviceType",
                    "required in a top-level rule: spec §6 scopes the top-level array to constraints"
                            + " spanning more than one service type, so each subject must name its own"));
        }
        if (subject.serviceType() != null && !handlersByServiceType.containsKey(subject.serviceType())) {
            findings.add(Finding.error(this, path + ".serviceType",
                    "names '" + subject.serviceType() + "', which no serviceTypes[] entry declares"));
        }

        switch (kind) {
            case TriggerMetadataModel.Subject.KIND_IDENTIFIER ->
                    rejectExtras(findings, subject, path, kind, false, false, false);
            case TriggerMetadataModel.Subject.KIND_ANNOTATION -> {
                rejectExtras(findings, subject, path, kind, true, false, false);
                requireAnnotationId(findings, subject.name(), annotationIds, path + ".name");
            }
            case TriggerMetadataModel.Subject.KIND_ANNOTATION_FIELD -> {
                rejectExtras(findings, subject, path, kind, false, true, false);
                requireAnnotationId(findings, subject.annotation(), annotationIds, path + ".annotation");
                if (DocumentWalk.safe(subject.path()).isEmpty()) {
                    findings.add(Finding.error(this, path + ".path",
                            "required: `annotationField` addresses one field inside the annotation"));
                }
            }
            case TriggerMetadataModel.Subject.KIND_HANDLER -> {
                rejectExtras(findings, subject, path, kind, true, false, false);
                requireHandler(findings, subject.name(), serviceTypeId, handlersByServiceType, path + ".name");
            }
            case TriggerMetadataModel.Subject.KIND_PARAM -> {
                rejectExtras(findings, subject, path, kind, true, false, true);
                requireHandler(findings, subject.handler(), serviceTypeId, handlersByServiceType,
                        path + ".handler");
                if (subject.name() == null || subject.name().isBlank()) {
                    findings.add(Finding.error(this, path + ".name",
                            "required: `param` addresses one parameter of a handler"));
                }
            }
            default -> findings.add(Finding.warn(this, path + ".kind",
                    "'" + kind + "' is not a subject kind this build implements, so the rule is skipped"
                            + " and states nothing here; spec §6.1 lists the implemented kinds"));
        }
    }

    private void rejectExtras(List<Finding> findings, TriggerMetadataModel.Subject subject, String path,
                              String kind, boolean allowsName, boolean allowsAnnotation,
                              boolean allowsHandler) {
        if (!allowsName && subject.name() != null) {
            findings.add(Finding.error(this, path + ".name", "`name` does not belong to kind '" + kind + "'"));
        }
        if (!allowsAnnotation && subject.annotation() != null) {
            findings.add(Finding.error(this, path + ".annotation",
                    "`annotation` belongs to kind 'annotationField'"));
        }
        if (!allowsAnnotation && subject.path() != null) {
            findings.add(Finding.error(this, path + ".path", "`path` belongs to kind 'annotationField'"));
        }
        if (!allowsHandler && subject.handler() != null) {
            findings.add(Finding.error(this, path + ".handler", "`handler` belongs to kind 'param'"));
        }
    }

    private void requireAnnotationId(List<Finding> findings, String id, Set<String> annotationIds,
                                     String path) {
        if (id == null || id.isBlank()) {
            findings.add(Finding.error(this, path, "required: the subject names no annotation"));
            return;
        }
        if (!annotationIds.contains(id)) {
            findings.add(Finding.error(this, path,
                    "names '" + id + "', which the annotations[] registry does not declare"));
        }
    }

    private void requireHandler(List<Finding> findings, String handler, String serviceTypeId,
                                Map<String, Set<String>> handlersByServiceType, String path) {
        if (handler == null || handler.isBlank()) {
            findings.add(Finding.error(this, path, "required: the subject names no handler"));
            return;
        }
        Set<String> names = handlersByServiceType.get(serviceTypeId);
        // An unknown service type is reported separately; a service type with no declared handlers at all
        // (a concrete one) cannot contradict the reference either, so neither case is reported twice here.
        if (names == null || names.isEmpty()) {
            return;
        }
        if (!names.contains(handler)) {
            findings.add(Finding.error(this, path,
                    "names handler '" + handler + "', which service type '" + serviceTypeId
                            + "' does not declare"));
        }
    }
}
