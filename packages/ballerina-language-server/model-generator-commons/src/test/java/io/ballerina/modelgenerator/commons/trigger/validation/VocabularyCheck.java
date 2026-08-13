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
import java.util.Set;

/**
 * <b>Spec §10's vocabulary tables</b>: every closed enumeration the spec defines, checked in one place.
 *
 * <p>This subsumes the plan's separately-named {@code HandlerKindCheck} and {@code IdentifierFormCheck},
 * and that is deliberate: both are pure membership tests against a §10 row, and splitting them would give
 * three classes that differ only in which row they read. The plan's own granularity note allows it —
 * checks are per invariant, and "a value outside its §10 vocabulary" is one invariant. What is <i>not</i>
 * folded in is {@link PresenceScopeCheck}, because scope is a different question from membership: it is
 * about where a legal value may appear, not whether the value is legal.
 *
 * <p>Every unknown value is an ERROR rather than a warning. The consuming pipeline degrades each of these
 * to a default — an unknown {@code kind} reads as {@code remote}, an unknown {@code addMode} as "at most
 * one", an unknown {@code form} yields a note and no placeholder — so a typo does not crash anything; it
 * quietly produces the wrong API guidance, which is worse.
 *
 * @since 1.10.0
 */
final class VocabularyCheck implements DocumentCheck {

    private static final Set<String> PRESENCE = Set.of("required", "optional");
    private static final Set<String> IDENTIFIER_FORM = Set.of("basePath", "stringLiteral");
    private static final Set<String> HANDLER_ADD_MODE = Set.of("subset", "many");
    private static final Set<String> PARAM_ADD_MODE = Set.of("many");
    private static final Set<String> KIND = Set.of("remote", "resource");
    private static final Set<String> ATTACH_POINT = Set.of("service", "function", "parameter", "return");
    private static final Set<String> IMPORT_TYPE = Set.of("driver");
    // Spec §9's shape vocabulary, which replaced the mode vocabulary. `element` is the narrower of the two:
    // an element is only ever bare or included, never itself an array or a stream.
    private static final Set<String> SHAPE_FORM = Set.of("bare", "array", "stream", "included");
    private static final Set<String> SHAPE_ELEMENT = Set.of("bare", "included");
    private static final Set<String> RULE_SEVERITY = Set.of("error", "warning");
    private static final Set<String> SUBJECT_KIND =
            Set.of("identifier", "annotation", "annotationField", "handler", "param");
    // Spec §2.1's two closed vocabularies.
    private static final Set<String> PLATFORM_SCOPE = Set.of("provided");
    private static final Set<String> NATIVE_OS = Set.of("linux", "windows", "macos");

    @Override
    public String id() {
        return "vocabulary";
    }

    @Override
    public String specSection() {
        return "§10";
    }

    @Override
    public List<Finding> check(TriggerMetadataModel document) {
        List<Finding> findings = new ArrayList<>();

        List<TriggerMetadataModel.Listener> listeners = DocumentWalk.safe(document.listeners());
        for (int i = 0; i < listeners.size(); i++) {
            TriggerMetadataModel.Listener listener = listeners.get(i);
            if (listener == null) {
                continue;
            }
            List<TriggerMetadataModel.RequiredImport> imports = DocumentWalk.safe(listener.requiredImports());
            for (int j = 0; j < imports.size(); j++) {
                if (imports.get(j) != null) {
                    member(findings, imports.get(j).importType(), IMPORT_TYPE,
                            "listeners[" + i + "].requiredImports[" + j + "].importType");
                }
            }
            List<TriggerMetadataModel.PlatformDependency> platform =
                    DocumentWalk.safe(listener.platformDependencies());
            for (int j = 0; j < platform.size(); j++) {
                TriggerMetadataModel.PlatformDependency dependency = platform.get(j);
                if (dependency == null) {
                    continue;
                }
                String path = "listeners[" + i + "].platformDependencies[" + j + "]";
                // Absent scope means "bundled", which is the common case, so only a stated value is checked.
                optionalMember(findings, dependency.scope(), PLATFORM_SCOPE, path + ".scope");
                List<TriggerMetadataModel.NativeLibrary> libraries =
                        DocumentWalk.safe(dependency.nativeLibraries());
                for (int k = 0; k < libraries.size(); k++) {
                    if (libraries.get(k) != null) {
                        member(findings, libraries.get(k).os(), NATIVE_OS,
                                path + ".nativeLibraries[" + k + "].os");
                    }
                }
            }
        }

        List<TriggerMetadataModel.ServiceType> serviceTypes = DocumentWalk.safe(document.serviceTypes());
        for (int i = 0; i < serviceTypes.size(); i++) {
            TriggerMetadataModel.ServiceType serviceType = serviceTypes.get(i);
            if (serviceType == null) {
                continue;
            }
            checkServiceType(findings, serviceType, i);
        }

        for (TriggerMetadataModel.Annotation annotation : DocumentWalk.safe(document.annotations())) {
            if (annotation == null) {
                continue;
            }
            String path = "annotations[" + annotation.id() + "]";
            member(findings, annotation.attachPoint(), ATTACH_POINT, path + ".attachPoint");
            member(findings, annotation.presence(), PRESENCE, path + ".presence");
        }

        BindingWalk.forEachBinding(document, (binding, path) -> {
            for (TriggerMetadataModel.TypedescVariant variant : DocumentWalk.safe(binding.typedescs())) {
                if (variant == null) {
                    continue;
                }
                for (TriggerMetadataModel.Shape shape : DocumentWalk.safe(variant.shapes())) {
                    if (shape != null) {
                        member(findings, shape.form(), SHAPE_FORM, path + ".typedescs[].shapes[].form");
                        optionalMember(findings, shape.element(), SHAPE_ELEMENT,
                                path + ".typedescs[].shapes[].element");
                    }
                }
            }
        });
        return findings;
    }

    private void checkServiceType(List<Finding> findings, TriggerMetadataModel.ServiceType serviceType,
                                  int index) {
        String path = DocumentWalk.serviceTypePath(index);
        if (serviceType.identifier() != null) {
            member(findings, serviceType.identifier().presence(), PRESENCE, path + ".identifier.presence");
            for (String form : DocumentWalk.safe(serviceType.identifier().form())) {
                member(findings, form, IDENTIFIER_FORM, path + ".identifier.form");
            }
        }
        // `rules[].rule` is an OPEN vocabulary (spec §6: "Adding a constraint is a new registry entry, not
        // a schema change"), so it is deliberately not checked for membership here — RuleRefCheck reports
        // an unimplemented id as a warning instead. `severity` and the subject `kind`s are closed.
        for (TriggerMetadataModel.Rule rule : DocumentWalk.safe(serviceType.rules())) {
            if (rule == null) {
                continue;
            }
            String rulePath = path + ".rules[" + rule.id() + "]";
            optionalMember(findings, rule.severity(), RULE_SEVERITY, rulePath + ".severity");
            for (TriggerMetadataModel.Subject subject : DocumentWalk.safe(rule.subjects())) {
                if (subject != null) {
                    member(findings, subject.kind(), SUBJECT_KIND, rulePath + ".subjects[].kind");
                }
            }
        }

        List<TriggerMetadataModel.ServiceType.HandlerOption> options = DocumentWalk.options(serviceType);
        for (int j = 0; j < options.size(); j++) {
            TriggerMetadataModel.ServiceType.HandlerOption option = options.get(j);
            if (option == null) {
                continue;
            }
            String optionPath = DocumentWalk.optionPath(index, j);
            member(findings, option.kind(), KIND, optionPath + ".kind");
            // Spec §5.1 moved addMode here from the handlers block. Absent reads as `subset`, so only a
            // stated value is checked.
            optionalMember(findings, option.addMode(), HANDLER_ADD_MODE, optionPath + ".addMode");
            optionalMember(findings, option.presence(), PRESENCE, optionPath + ".presence");
            if (option.accessor() != null) {
                member(findings, option.accessor().presence(), PRESENCE, optionPath + ".accessor.presence");
            }
            if (option.path() != null) {
                member(findings, option.path().presence(), PRESENCE, optionPath + ".path.presence");
            }
            List<TriggerMetadataModel.ServiceType.Param> params = DocumentWalk.safe(option.params());
            for (int k = 0; k < params.size(); k++) {
                TriggerMetadataModel.ServiceType.Param param = params.get(k);
                if (param == null) {
                    continue;
                }
                String paramPath = DocumentWalk.paramPath(index, j, k);
                member(findings, param.presence(), PRESENCE, paramPath + ".presence");
                optionalMember(findings, param.addMode(), PARAM_ADD_MODE, paramPath + ".addMode");
            }
        }
    }

    /** A value the spec requires: absent is a defect, and so is a value outside the table. */
    private void member(List<Finding> findings, String value, Set<String> allowed, String path) {
        if (value == null) {
            findings.add(Finding.error(this, path, "missing; spec §10 allows " + sorted(allowed)));
            return;
        }
        optionalMember(findings, value, allowed, path);
    }

    /** A value the spec allows to be absent: only a present-but-unknown value is a defect. */
    private void optionalMember(List<Finding> findings, String value, Set<String> allowed, String path) {
        if (value != null && !allowed.contains(value)) {
            findings.add(Finding.error(this, path,
                    "'" + value + "' is outside spec §10's vocabulary " + sorted(allowed)));
        }
    }

    private static String sorted(Set<String> allowed) {
        return allowed.stream().sorted().toList().toString();
    }
}
