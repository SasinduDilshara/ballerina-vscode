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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Owns <b>spec §6 {@code rules[]}</b>: the cross-construct constraints a service type declares.
 *
 * <h2>From a closed enum to an open registry</h2>
 *
 * <p>Spec v1.0 replaced {@code type: "oneOf" | "atMostOne"} with {@code rule: "<namespace>.<id>"} drawn from
 * an open registry, and replaced the three fixed member shapes with a tagged union of five subject kinds.
 * The consequence for this component is that <b>the registry, not the schema, is the unit of extension</b>:
 * a new constraint is one entry in {@link Kind}, and nothing else in the pipeline changes.
 *
 * <p>All six registry entries §6.2 defines are implemented, though only three appear in the corpus. They
 * cost one enum constant each, and implementing them now is what keeps the first document to use
 * {@code structure.requires} from silently rendering nothing.
 *
 * <h2>Skip, do not fail</h2>
 *
 * <p>Spec §6 is explicit: "A consumer that does not recognise a {@code rule} id or a subject {@code kind}
 * skips that rule with a logged warning and never fails. This is what lets an older consumer read a newer
 * manifest." That policy is load-bearing for §11's versioning story — it is precisely what makes a new
 * constraint kind a <i>minor</i> bump — so an unknown id is skipped here rather than vetoed, and the
 * validator reports it against this repo's own corpus instead.
 *
 * <h2>Preference moved from the member to the rule</h2>
 *
 * <p>{@code preferred: true} on a member became a rule-level {@code prefer: "<role>"}. The distinction is
 * real: a role is named once and can be referred to from {@code prefer} and {@code reportOn} both, whereas a
 * per-member flag could not express "report against this one but default to that one".
 *
 * @since 1.7.0
 */
final class ConstraintResolver {

    private static final Logger LOGGER = Logger.getLogger(ConstraintResolver.class.getName());

    private ConstraintResolver() {
        // Prevent instantiation
    }

    /** The registry entries this build implements (spec §6.2). */
    enum Kind {
        /** Exactly one subject present — not zero, not more than one. */
        EXACTLY_ONE(TriggerMetadataModel.Rule.RULE_EXACTLY_ONE),
        /** Zero or one subject — never more, but zero is fine. */
        AT_MOST_ONE(TriggerMetadataModel.Rule.RULE_AT_MOST_ONE),
        /** One or more subjects present. */
        AT_LEAST_ONE(TriggerMetadataModel.Rule.RULE_AT_LEAST_ONE),
        /** All subjects present, or none of them. */
        ALL_OR_NONE(TriggerMetadataModel.Rule.RULE_ALL_OR_NONE),
        /** If {@code when} is present, {@code then} must be present. */
        REQUIRES(TriggerMetadataModel.Rule.RULE_REQUIRES),
        /** If {@code when} is present, {@code then} must be absent. */
        CONFLICTS_WITH(TriggerMetadataModel.Rule.RULE_CONFLICTS_WITH);

        private final String registryId;

        Kind(String registryId) {
            this.registryId = registryId;
        }

        /** The registry id a document writes. */
        String registryId() {
            return registryId;
        }

        /** Whether this constraint's subjects are interchangeable, or fixed as {@code when}/{@code then}. */
        boolean isAsymmetric() {
            return this == REQUIRES || this == CONFLICTS_WITH;
        }

        static Kind of(String registryId) {
            for (Kind kind : values()) {
                if (kind.registryId.equals(registryId)) {
                    return kind;
                }
            }
            return null;
        }
    }

    /**
     * One resolved subject. Spec §6.1's tagged union, flattened to the fields a consumer renders.
     *
     * <p>Sealed so a new subject kind cannot be added without every consumer being forced to handle it: the
     * renderer switches over these, and a silently unhandled kind would drop an alternative from a
     * constraint that is only correct when all of its alternatives are stated.
     */
    sealed interface Subject {

        /** This subject's name within its rule; {@code null} when the document labels none. */
        String role();

        /**
         * The enclosing service type's identifier slot.
         *
         * @param role the subject's role label
         */
        record Identifier(String role) implements Subject {
        }

        /**
         * An annotation as a whole — its presence, rather than a field inside it.
         *
         * @param annotationId   the {@code annotations[].id} referenced
         * @param annotationName the annotation's actual name, resolved through the §8 registry, because the
         *                       id is not what a reader writes
         * @param role           the subject's role label
         */
        record Annotation(String annotationId, String annotationName, String role) implements Subject {
        }

        /**
         * A field inside an annotation's record, e.g. {@code @rabbitmq:ServiceConfig}'s {@code queueName}.
         *
         * @param annotationId   the {@code annotations[].id} referenced
         * @param annotationName the annotation's actual name, resolved through the §8 registry
         * @param path           the field path, which spec v1.0 made an array so a nested field is reachable
         * @param role           the subject's role label
         */
        record AnnotationField(String annotationId, String annotationName, List<String> path, String role)
                implements Subject {
        }

        /**
         * One of the service type's handlers.
         *
         * @param name the {@code handlers.options[].name} referenced
         * @param role the subject's role label
         */
        record Handler(String name, String role) implements Subject {
        }

        /**
         * One parameter of one handler.
         *
         * @param handler the handler the parameter belongs to
         * @param name    the parameter's name
         * @param role    the subject's role label
         */
        record Param(String handler, String name, String role) implements Subject {
        }
    }

    /**
     * One resolved rule.
     *
     * @param id       the document's local rule id, carried for diagnostics and for the rendered note
     * @param kind     the constraint's semantics
     * @param subjects the subjects it ranges over, in document order; never fewer than two
     * @param message  the document's authored diagnostic text, or {@code null}. Preferred over a
     *                 synthesized sentence when present: it is written by whoever knows the connector, and
     *                 says <i>why</i>, which no amount of structure can reconstruct
     * @param severity {@code "warning"} when the document downgrades the rule; {@code null} for the default
     *                 {@code error}
     * @param prefer   the {@code role} a generator should default to, or {@code null}
     */
    record Constraint(String id, Kind kind, List<Subject> subjects, String message, String severity,
                      String prefer) {
    }

    /**
     * Resolves a service type's rules.
     *
     * <p>A rule is dropped whole, with a warning, when it names an unimplemented registry id or when fewer
     * than two usable subjects survive — a one-alternative "choose exactly one of" is not a constraint a
     * reader can act on, and stating it would be noise at best and misleading at worst.
     *
     * @param libraryName          the library, for log attribution only
     * @param rules                the rules to resolve; may be {@code null}
     * @param declaredHandlerNames the handler names this service type actually declares, used to drop a
     *                             handler subject that names something absent. Pass {@code null} when the
     *                             catalog is not knowable, which suppresses the cross-check rather than
     *                             dropping every handler subject; an <b>empty</b> set means "this service
     *                             type declares no handlers" and does drop them
     * @param annotations          spec §8's registry, the single lookup from a subject's annotation id to
     *                             the annotation it names; may be {@code null}, which suppresses the
     *                             resolution and keeps the id as the name
     * @return the resolved rules, in document order
     */
    static List<Constraint> resolve(String libraryName,
                                    List<TriggerMetadataModel.Rule> rules,
                                    Set<String> declaredHandlerNames,
                                    AnnotationRegistry annotations) {
        List<Constraint> resolved = new ArrayList<>();
        if (rules == null) {
            return resolved;
        }
        for (TriggerMetadataModel.Rule rule : rules) {
            if (rule == null) {
                continue;
            }
            Kind kind = Kind.of(rule.rule());
            if (kind == null) {
                // Spec §6's skip-unknown policy, which is what lets an older consumer read a newer manifest.
                LOGGER.warning("Skipped rule '" + rule.id() + "' for " + libraryName
                        + ": '" + rule.rule() + "' is not a registry entry this build implements");
                continue;
            }
            List<Subject> subjects = subjects(libraryName, rule, declaredHandlerNames, annotations);
            if (subjects.size() < 2) {
                LOGGER.warning("Skipped rule '" + rule.id() + "' for " + libraryName + ": "
                        + subjects.size() + " usable subject(s) — a constraint needs at least two");
                continue;
            }
            if (kind.isAsymmetric() && !hasBothRoles(subjects)) {
                // Without the roles there is no way to tell the antecedent from the consequent, and
                // guessing inverts the constraint — which is worse than saying nothing.
                LOGGER.warning("Skipped rule '" + rule.id() + "' for " + libraryName + ": '"
                        + kind.registryId() + "' is asymmetric but its subjects carry no `"
                        + TriggerMetadataModel.Rule.ROLE_WHEN + "`/`"
                        + TriggerMetadataModel.Rule.ROLE_THEN + "` roles");
                continue;
            }
            resolved.add(new Constraint(rule.id(), kind, subjects, blankToNull(rule.message()),
                    blankToNull(rule.severity()), blankToNull(rule.prefer())));
        }
        return resolved;
    }

    private static boolean hasBothRoles(List<Subject> subjects) {
        boolean when = false;
        boolean then = false;
        for (Subject subject : subjects) {
            when |= TriggerMetadataModel.Rule.ROLE_WHEN.equals(subject.role());
            then |= TriggerMetadataModel.Rule.ROLE_THEN.equals(subject.role());
        }
        return when && then;
    }

    private static List<Subject> subjects(String libraryName, TriggerMetadataModel.Rule rule,
                                          Set<String> declaredHandlerNames, AnnotationRegistry annotations) {
        List<Subject> subjects = new ArrayList<>();
        if (rule.subjects() == null) {
            return subjects;
        }
        for (TriggerMetadataModel.Subject subject : rule.subjects()) {
            if (subject == null || subject.kind() == null) {
                continue;
            }
            Subject resolved = switch (subject.kind()) {
                case TriggerMetadataModel.Subject.KIND_IDENTIFIER ->
                        new Subject.Identifier(subject.role());
                case TriggerMetadataModel.Subject.KIND_ANNOTATION ->
                        annotationSubject(libraryName, rule, subject.name(), null, subject.role(),
                                annotations);
                case TriggerMetadataModel.Subject.KIND_ANNOTATION_FIELD ->
                        annotationSubject(libraryName, rule, subject.annotation(),
                                nonEmpty(subject.path()), subject.role(), annotations);
                case TriggerMetadataModel.Subject.KIND_HANDLER ->
                        handlerSubject(libraryName, rule, subject.name(), subject.role(),
                                declaredHandlerNames);
                case TriggerMetadataModel.Subject.KIND_PARAM ->
                        paramSubject(libraryName, rule, subject, declaredHandlerNames);
                default -> {
                    LOGGER.warning("Dropped subject of rule '" + rule.id() + "' for " + libraryName
                            + ": '" + subject.kind() + "' is not a subject kind this build implements");
                    yield null;
                }
            };
            if (resolved != null) {
                subjects.add(resolved);
            }
        }
        return subjects;
    }

    private static Subject annotationSubject(String libraryName, TriggerMetadataModel.Rule rule, String id,
                                             List<String> path, String role, AnnotationRegistry annotations) {
        if (id == null || id.isBlank()) {
            LOGGER.warning("Dropped subject of rule '" + rule.id() + "' for " + libraryName
                    + ": it names no annotation");
            return null;
        }
        String name = annotationName(id, annotations);
        if (name == null) {
            // The rule references a registry entry that does not exist, so there is no annotation for a
            // reader to attach. Same policy as a phantom handler: drop it and say why.
            LOGGER.warning("Dropped subject of rule '" + rule.id() + "' for " + libraryName
                    + ": annotation id '" + id + "' is not in annotations[]");
            return null;
        }
        return path == null ? new Subject.Annotation(id, name, role)
                : new Subject.AnnotationField(id, name, path, role);
    }

    private static Subject handlerSubject(String libraryName, TriggerMetadataModel.Rule rule, String name,
                                          String role, Set<String> declaredHandlerNames) {
        if (name == null || name.isBlank()) {
            LOGGER.warning("Dropped subject of rule '" + rule.id() + "' for " + libraryName
                    + ": it names no handler");
            return null;
        }
        // A rule referencing a handler this service type does not declare is a document defect: the
        // constraint could never be satisfied through that alternative. Drop it and say so, rather than
        // telling the model to choose between a real handler and a phantom.
        if (declaredHandlerNames != null && !declaredHandlerNames.contains(name)) {
            LOGGER.warning("Dropped subject of rule '" + rule.id() + "' for " + libraryName
                    + ": handler '" + name + "' is not declared by this service type");
            return null;
        }
        return new Subject.Handler(name, role);
    }

    private static Subject paramSubject(String libraryName, TriggerMetadataModel.Rule rule,
                                        TriggerMetadataModel.Subject subject,
                                        Set<String> declaredHandlerNames) {
        if (subject.handler() == null || subject.handler().isBlank()
                || subject.name() == null || subject.name().isBlank()) {
            LOGGER.warning("Dropped subject of rule '" + rule.id() + "' for " + libraryName
                    + ": a param subject needs both `handler` and `name`");
            return null;
        }
        if (declaredHandlerNames != null && !declaredHandlerNames.contains(subject.handler())) {
            LOGGER.warning("Dropped subject of rule '" + rule.id() + "' for " + libraryName
                    + ": handler '" + subject.handler() + "' is not declared by this service type");
            return null;
        }
        return new Subject.Param(subject.handler(), subject.name(), subject.role());
    }

    /**
     * The name of the annotation a subject references, via spec §8's registry.
     *
     * <p>With no registry the id is returned unchanged, so a caller exercising rule semantics without a
     * document still gets a usable name.
     */
    private static String annotationName(String annotationId, AnnotationRegistry annotations) {
        if (annotations == null) {
            return annotationId;
        }
        return annotations.byId(annotationId)
                .map(annotation -> annotation.type() == null ? null : annotation.type().name())
                .orElse(null);
    }

    private static List<String> nonEmpty(List<String> path) {
        if (path == null || path.isEmpty()) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String segment : path) {
            if (segment != null && !segment.isBlank()) {
                seen.add(segment);
            }
        }
        return List.copyOf(seen);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
