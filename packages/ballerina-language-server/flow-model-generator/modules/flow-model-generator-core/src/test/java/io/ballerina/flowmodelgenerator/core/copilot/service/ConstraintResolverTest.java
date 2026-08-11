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
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Set;

/**
 * Conformance tests for <b>spec §6 {@code rules[]}</b>, written against the spec text.
 *
 * <p>Spec v1.0 replaced the closed {@code oneOf}/{@code atMostOne} enum with an open registry and replaced
 * the three fixed member shapes with five subject kinds. These pin the two properties that makes that
 * design work: every implemented registry entry resolves, and an <i>un</i>implemented one is skipped
 * rather than failing the library.
 *
 * @since 1.7.0
 */
public class ConstraintResolverTest {

    private static final String LIB = "ballerinax/testlib";
    private static final Set<String> HANDLERS = Set.of("onMessage", "onRequest", "onError");

    @Test
    public void testEveryRegistryEntryTheSpecDefinesIsImplemented() {
        // Spec §6.2 lists six. Only three appear in the corpus, but an unimplemented entry is silently
        // skipped, so the first document to use `structure.requires` would otherwise render nothing.
        for (String registryId : List.of(
                TriggerMetadataModel.Rule.RULE_EXACTLY_ONE,
                TriggerMetadataModel.Rule.RULE_AT_MOST_ONE,
                TriggerMetadataModel.Rule.RULE_AT_LEAST_ONE,
                TriggerMetadataModel.Rule.RULE_ALL_OR_NONE,
                TriggerMetadataModel.Rule.RULE_REQUIRES,
                TriggerMetadataModel.Rule.RULE_CONFLICTS_WITH)) {
            Assert.assertNotNull(ConstraintResolver.Kind.of(registryId),
                    registryId + " is defined by spec §6.2 but not implemented");
        }
    }

    @Test
    public void testAnUnimplementedRuleIdIsSkippedRatherThanFailing() {
        // Spec §6: "A consumer that does not recognise a rule id ... skips that rule with a logged warning
        // and never fails. This is what lets an older consumer read a newer manifest." That policy is what
        // makes a new constraint kind a MINOR bump under §11.
        List<ConstraintResolver.Constraint> resolved = ConstraintResolver.resolve(LIB,
                List.of(rule("$r", "structure.someFutureThing", null, null,
                        handler("onMessage", null), handler("onRequest", null))),
                HANDLERS, null);
        Assert.assertTrue(resolved.isEmpty());
    }

    @Test
    public void testAnUnknownSubjectKindIsSkippedRatherThanFailing() {
        // Same policy, one tier down. The rule survives only if enough other subjects do; here it does not.
        List<ConstraintResolver.Constraint> resolved = ConstraintResolver.resolve(LIB,
                List.of(rule("$r", TriggerMetadataModel.Rule.RULE_EXACTLY_ONE, null, null,
                        subject("someFutureKind", null, null, null, null, null),
                        handler("onMessage", null))),
                HANDLERS, null);
        Assert.assertTrue(resolved.isEmpty());
    }

    @Test
    public void testExactlyOneAndAtMostOneStayDistinct() {
        // Collapsing them would either invent an obligation (websocket does not require onMessage) or drop
        // one (rabbitmq does require a queue-name source).
        Assert.assertEquals(resolveKind(TriggerMetadataModel.Rule.RULE_EXACTLY_ONE),
                ConstraintResolver.Kind.EXACTLY_ONE);
        Assert.assertEquals(resolveKind(TriggerMetadataModel.Rule.RULE_AT_MOST_ONE),
                ConstraintResolver.Kind.AT_MOST_ONE);
    }

    @Test
    public void testAHandlerSubjectNamingAnUndeclaredHandlerIsDropped() {
        // A constraint that could never be satisfied through that alternative would tell the model to
        // choose between a real handler and a phantom. With only one usable subject left, the rule goes.
        List<ConstraintResolver.Constraint> resolved = ConstraintResolver.resolve(LIB,
                List.of(rule("$r", TriggerMetadataModel.Rule.RULE_EXACTLY_ONE, null, null,
                        handler("onMessage", null), handler("onNoSuchHandler", null))),
                HANDLERS, null);
        Assert.assertTrue(resolved.isEmpty());
    }

    @Test
    public void testTheHandlerCrossCheckIsSuppressedWhenTheCatalogIsNotKnowable() {
        // A null set means "the catalog could not be determined", which must not empty every rule. An
        // unresolvable service type is already vetoed elsewhere; it must not also silently delete rules.
        List<ConstraintResolver.Constraint> resolved = ConstraintResolver.resolve(LIB,
                List.of(rule("$r", TriggerMetadataModel.Rule.RULE_EXACTLY_ONE, null, null,
                        handler("onAnything", null), handler("onAnythingElse", null))),
                null, null);
        Assert.assertEquals(resolved.size(), 1);
    }

    @Test
    public void testAnAsymmetricRuleWithoutRolesIsDropped() {
        // Spec §6 fixes `when`/`then` for the asymmetric entries. Without them there is no way to tell the
        // antecedent from the consequent, and guessing inverts the constraint -- worse than saying nothing.
        List<ConstraintResolver.Constraint> resolved = ConstraintResolver.resolve(LIB,
                List.of(rule("$r", TriggerMetadataModel.Rule.RULE_REQUIRES, null, null,
                        handler("onMessage", null), handler("onRequest", null))),
                HANDLERS, null);
        Assert.assertTrue(resolved.isEmpty());
    }

    @Test
    public void testAnAsymmetricRuleWithRolesSurvives() {
        List<ConstraintResolver.Constraint> resolved = ConstraintResolver.resolve(LIB,
                List.of(rule("$r", TriggerMetadataModel.Rule.RULE_REQUIRES, null, null,
                        handler("onMessage", TriggerMetadataModel.Rule.ROLE_WHEN),
                        handler("onRequest", TriggerMetadataModel.Rule.ROLE_THEN))),
                HANDLERS, null);
        Assert.assertEquals(resolved.size(), 1);
        Assert.assertEquals(resolved.get(0).kind(), ConstraintResolver.Kind.REQUIRES);
    }

    @Test
    public void testTheAuthoredMessageAndPreferenceAreCarried() {
        // The document's own sentence says WHY a constraint exists, which no amount of structure
        // reconstructs; `prefer` names a role rather than flagging a member, so it survives the move.
        List<ConstraintResolver.Constraint> resolved = ConstraintResolver.resolve(LIB,
                List.of(rule("$queueNameSource", TriggerMetadataModel.Rule.RULE_EXACTLY_ONE,
                        "A RabbitMQ consumer needs its queue name from exactly one source.", "fromAnnotation",
                        subject(TriggerMetadataModel.Subject.KIND_ANNOTATION_FIELD, null, "$serviceConfig",
                                List.of("queueName"), null, "fromAnnotation"),
                        subject(TriggerMetadataModel.Subject.KIND_IDENTIFIER, null, null, null, null,
                                "fromIdentifier"))),
                HANDLERS, registry());
        Assert.assertEquals(resolved.size(), 1);
        ConstraintResolver.Constraint constraint = resolved.get(0);
        Assert.assertEquals(constraint.message(),
                "A RabbitMQ consumer needs its queue name from exactly one source.");
        Assert.assertEquals(constraint.prefer(), "fromAnnotation");
    }

    @Test
    public void testAnAnnotationSubjectResolvesTheIdToTheNameAReaderWrites() {
        // The document says `$serviceConfig`, a registry id; what a reader writes is `@…:ServiceConfig`.
        // Rendering the id would put a name in the prompt that exists nowhere.
        List<ConstraintResolver.Constraint> resolved = ConstraintResolver.resolve(LIB,
                List.of(rule("$r", TriggerMetadataModel.Rule.RULE_EXACTLY_ONE, null, null,
                        subject(TriggerMetadataModel.Subject.KIND_ANNOTATION_FIELD, null, "$serviceConfig",
                                List.of("queueName"), null, null),
                        subject(TriggerMetadataModel.Subject.KIND_IDENTIFIER, null, null, null, null, null))),
                HANDLERS, registry());
        ConstraintResolver.Subject.AnnotationField field =
                (ConstraintResolver.Subject.AnnotationField) resolved.get(0).subjects().get(0);
        Assert.assertEquals(field.annotationId(), "$serviceConfig");
        Assert.assertEquals(field.annotationName(), "ServiceConfig");
        Assert.assertEquals(field.path(), List.of("queueName"));
    }

    @Test
    public void testASubjectNamingAnUndeclaredAnnotationIsDropped() {
        List<ConstraintResolver.Constraint> resolved = ConstraintResolver.resolve(LIB,
                List.of(rule("$r", TriggerMetadataModel.Rule.RULE_EXACTLY_ONE, null, null,
                        subject(TriggerMetadataModel.Subject.KIND_ANNOTATION_FIELD, null, "$nope",
                                List.of("field"), null, null),
                        subject(TriggerMetadataModel.Subject.KIND_IDENTIFIER, null, null, null, null, null))),
                HANDLERS, registry());
        Assert.assertTrue(resolved.isEmpty());
    }

    @Test
    public void testANestedAnnotationFieldPathIsKeptWhole() {
        // Spec §6.1 made `path` an array precisely so a nested field is reachable; truncating it to the
        // first segment would address the wrong field.
        List<ConstraintResolver.Constraint> resolved = ConstraintResolver.resolve(LIB,
                List.of(rule("$r", TriggerMetadataModel.Rule.RULE_AT_MOST_ONE, null, null,
                        subject(TriggerMetadataModel.Subject.KIND_ANNOTATION_FIELD, null, "$serviceConfig",
                                List.of("retryConfig", "maxCount"), null, null),
                        subject(TriggerMetadataModel.Subject.KIND_IDENTIFIER, null, null, null, null, null))),
                HANDLERS, registry());
        ConstraintResolver.Subject.AnnotationField field =
                (ConstraintResolver.Subject.AnnotationField) resolved.get(0).subjects().get(0);
        Assert.assertEquals(field.path(), List.of("retryConfig", "maxCount"));
    }

    @Test
    public void testAParamSubjectCarriesItsHandlerAndName() {
        List<ConstraintResolver.Constraint> resolved = ConstraintResolver.resolve(LIB,
                List.of(rule("$r", TriggerMetadataModel.Rule.RULE_REQUIRES, null, null,
                        subject(TriggerMetadataModel.Subject.KIND_PARAM, "batchSize", null, null,
                                "onMessage", TriggerMetadataModel.Rule.ROLE_WHEN),
                        subject(TriggerMetadataModel.Subject.KIND_ANNOTATION, "$serviceConfig", null, null,
                                null, TriggerMetadataModel.Rule.ROLE_THEN))),
                HANDLERS, registry());
        Assert.assertEquals(resolved.size(), 1);
        ConstraintResolver.Subject.Param param =
                (ConstraintResolver.Subject.Param) resolved.get(0).subjects().get(0);
        Assert.assertEquals(param.handler(), "onMessage");
        Assert.assertEquals(param.name(), "batchSize");
    }

    @Test
    public void testARuleWithFewerThanTwoUsableSubjectsIsDropped() {
        // A one-alternative "choose exactly one of" is not a constraint a reader can act on.
        List<ConstraintResolver.Constraint> resolved = ConstraintResolver.resolve(LIB,
                List.of(rule("$r", TriggerMetadataModel.Rule.RULE_EXACTLY_ONE, null, null,
                        handler("onMessage", null))),
                HANDLERS, null);
        Assert.assertTrue(resolved.isEmpty());
    }

    // ---- fixtures --------------------------------------------------------------------

    private static ConstraintResolver.Kind resolveKind(String registryId) {
        return ConstraintResolver.resolve(LIB,
                List.of(rule("$r", registryId, null, null,
                        handler("onMessage", null), handler("onRequest", null))),
                HANDLERS, null).get(0).kind();
    }

    private static AnnotationRegistry registry() {
        return AnnotationRegistry.of(new TriggerMetadataModel("v1.0", null, null,
                List.of(new TriggerMetadataModel.Annotation("$serviceConfig",
                        new TypeRef("ServiceConfig", null),
                        TriggerMetadataModel.Annotation.ATTACH_POINT_SERVICE,
                        TriggerMetadataModel.Annotation.PRESENCE_OPTIONAL)),
                null));
    }

    private static TriggerMetadataModel.Rule rule(String id, String registryId, String message, String prefer,
                                                  TriggerMetadataModel.Subject... subjects) {
        return new TriggerMetadataModel.Rule(id, registryId, List.of(subjects), null, message, prefer);
    }

    private static TriggerMetadataModel.Subject handler(String name, String role) {
        return subject(TriggerMetadataModel.Subject.KIND_HANDLER, name, null, null, null, role);
    }

    private static TriggerMetadataModel.Subject subject(String kind, String name, String annotation,
                                                        List<String> path, String handler, String role) {
        return new TriggerMetadataModel.Subject(kind, name, annotation, path, handler, null, role);
    }
}
