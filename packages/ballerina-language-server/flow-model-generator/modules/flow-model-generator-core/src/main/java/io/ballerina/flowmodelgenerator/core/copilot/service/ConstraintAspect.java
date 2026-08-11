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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.ballerina.modelgenerator.commons.trigger.models.TriggerMetadataModel;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Spec §6 {@code rules[]} — the exclusivity constraints a service type declares.
 *
 * <h2>Why this runs before the handler catalog</h2>
 *
 * <p>{@link ConstraintResolver} needs the set of handler names this service type declares, so that a
 * {@code {handler}} member naming something absent can be dropped rather than offered to the model as a
 * choice. Those names could come from two places: the built {@link ServiceDraft} (which would force this to
 * run <i>after</i> {@link HandlerCatalogAspect}) or the document and semantic model directly.
 *
 * <p>This component takes the second route, so it stays a pure function of its inputs and the registry keeps
 * a single meaningful ordering rule ("the catalog runs last") instead of two. The names are sourced the same
 * way the catalog itself decides them — by asking {@link HandlerCatalogResolver} which kind of catalog this
 * service type has — so the two can never disagree about what a handler is:
 * <ul>
 *   <li>a <b>marker</b> type's names are its non-wildcard {@code options[].name} values;</li>
 *   <li>a <b>concrete</b> type's names come from the semantic model, the same source the catalog reads.</li>
 * </ul>
 *
 * <p>When neither is available — no facts, or a concrete type whose object type does not resolve — the set is
 * passed as {@code null}, which suppresses the cross-check rather than dropping every handler member. An
 * unresolvable type is already vetoed by {@link ServiceIdentityAspect} or the catalog; it must not
 * additionally cause a rule to be silently emptied.
 *
 * @since 1.7.0
 */
final class ConstraintAspect implements ServiceAspect {

    @Override
    public String id() {
        return "constraints";
    }

    @Override
    public String specSection() {
        return "§6";
    }

    @Override
    public void contribute(TriggerScope scope, ServiceDraft draft) {
        TriggerMetadataModel.ServiceType serviceType = scope.serviceType();
        if (serviceType == null || serviceType.rules() == null || serviceType.rules().isEmpty()) {
            return;
        }
        List<ConstraintResolver.Constraint> constraints = ConstraintResolver.resolve(
                scope.libraryName(), serviceType.rules(), declaredHandlerNames(scope, serviceType),
                scope.annotations());
        if (constraints.isEmpty()) {
            return;
        }
        JsonArray json = new JsonArray();
        constraints.forEach(constraint -> json.add(toJson(constraint)));
        draft.setConstraints(json);
    }

    /**
     * The handler names this service type declares, or {@code null} when the catalog is not knowable.
     */
    private static Set<String> declaredHandlerNames(TriggerScope scope,
                                                    TriggerMetadataModel.ServiceType serviceType) {
        if (!HandlerCatalogResolver.isConcrete(serviceType)) {
            Set<String> names = new LinkedHashSet<>();
            List<TriggerMetadataModel.ServiceType.HandlerOption> options =
                    serviceType.handlers() == null ? null : serviceType.handlers().options();
            if (options == null) {
                return null;
            }
            for (TriggerMetadataModel.ServiceType.HandlerOption option : options) {
                if (option == null || option.name() == null
                        || TriggerMetadataModel.ServiceType.HandlerOption.WILDCARD_NAME
                                .equals(option.name())) {
                    continue;
                }
                names.add(option.name());
            }
            return names;
        }
        if (scope.facts() == null || scope.serviceTypeName() == null) {
            return null;
        }
        return scope.facts().serviceObjectType(scope.serviceTypeName())
                .map(objectType -> {
                    Set<String> names = new LinkedHashSet<>();
                    scope.facts().declaredMethods(objectType)
                            .forEach(method -> names.add(method.name()));
                    return names;
                })
                .orElse(null);
    }

    /**
     * Wire shape: {@code {id?, rule, subjects[], message?, severity?, prefer?}}.
     *
     * <p>The registry id is emitted verbatim rather than a normalized enum name, so a consumer states what
     * the document states. {@code message} is carried because the document's own sentence says <i>why</i> a
     * constraint exists, which no amount of structure reconstructs — a renderer should prefer it over
     * anything it can synthesize from the subjects.
     */
    private static JsonObject toJson(ConstraintResolver.Constraint constraint) {
        JsonObject json = new JsonObject();
        if (constraint.id() != null && !constraint.id().isEmpty()) {
            json.addProperty("id", constraint.id());
        }
        json.addProperty("rule", constraint.kind().registryId());
        JsonArray subjects = new JsonArray();
        for (ConstraintResolver.Subject subject : constraint.subjects()) {
            subjects.add(subjectToJson(subject));
        }
        json.add("subjects", subjects);
        if (constraint.message() != null) {
            json.addProperty("message", constraint.message());
        }
        // Emitted only when the document downgrades the rule; `error` is the default and stating it would
        // break the omission rule.
        if (TriggerMetadataModel.Rule.SEVERITY_WARNING.equals(constraint.severity())) {
            json.addProperty("severity", constraint.severity());
        }
        if (constraint.prefer() != null) {
            json.addProperty("prefer", constraint.prefer());
        }
        return json;
    }

    private static JsonObject subjectToJson(ConstraintResolver.Subject subject) {
        JsonObject json = new JsonObject();
        switch (subject) {
            case ConstraintResolver.Subject.Identifier ignored ->
                    json.addProperty("kind", TriggerMetadataModel.Subject.KIND_IDENTIFIER);
            case ConstraintResolver.Subject.Annotation annotation -> {
                json.addProperty("kind", TriggerMetadataModel.Subject.KIND_ANNOTATION);
                // The resolved name is what a reader must write; the id is kept so the wire still says
                // which registry entry the rule referenced.
                json.addProperty("annotation", annotation.annotationName());
                json.addProperty("annotationId", annotation.annotationId());
            }
            case ConstraintResolver.Subject.AnnotationField field -> {
                json.addProperty("kind", TriggerMetadataModel.Subject.KIND_ANNOTATION_FIELD);
                json.addProperty("annotation", field.annotationName());
                json.addProperty("annotationId", field.annotationId());
                JsonArray path = new JsonArray();
                field.path().forEach(path::add);
                json.add("path", path);
            }
            case ConstraintResolver.Subject.Handler handler -> {
                json.addProperty("kind", TriggerMetadataModel.Subject.KIND_HANDLER);
                json.addProperty("name", handler.name());
            }
            case ConstraintResolver.Subject.Param param -> {
                json.addProperty("kind", TriggerMetadataModel.Subject.KIND_PARAM);
                json.addProperty("handler", param.handler());
                json.addProperty("name", param.name());
            }
        }
        if (subject.role() != null) {
            json.addProperty("role", subject.role());
        }
        return json;
    }
}
