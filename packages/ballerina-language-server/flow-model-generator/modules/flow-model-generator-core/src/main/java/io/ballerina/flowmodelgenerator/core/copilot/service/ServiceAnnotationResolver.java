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

import java.util.List;

/**
 * Owns <b>spec §8 at {@code attachPoint: "service"}</b>: which annotations the generated service must or
 * may carry.
 *
 * <p><b>Spec v1.0 closed §8's "Residual gap".</b> Service scope used to be the one point with no precise
 * reference, so it was selected by attach point and filtered by a reverse {@code appliesTo} list. It now
 * has the same forward reference every other point has — {@code serviceTypes[].annotations} — so this
 * resolver reads the registry <b>by id</b>, exactly as the handler, parameter and return scopes do. The
 * reverse list, and the ambiguity of what an absent one meant, are both gone.
 *
 * <p><b>There is deliberately no fallback.</b> An annotation the service type does not reference attaches
 * nowhere, and that is reported by the validator rather than guessed at here. Two published corpus
 * documents ({@code smb}, {@code rabbitmq}) reach their service annotation only from a rule subject, which
 * §8's table does not make a reference site; treating a rule as one would give an annotation a second,
 * implicit way to attach, and a rule says what an annotation <i>relates to</i>, not where it goes.
 *
 * <h2>One decision of ours, not the spec's</h2>
 *
 * <p><b>{@code annotations[].type} names the annotation, not its constraint.</b> Verified against the
 * corpus: {@code ballerina/ftp}'s document declares {@code type: {"name": "ServiceConfig"}} while the
 * package declares {@code public annotation ServiceConfiguration ServiceConfig on service;} — so the
 * document's name is the tag written after {@code @}, and the constraining record carries a different
 * name entirely ({@code smb}: {@code SmbServiceConfig}; {@code websub}:
 * {@code SubscriberServiceConfiguration}; {@code ballerinax/cdc}: {@code CdcServiceConfig}). Reading the
 * document's name as a type name would emit an attachment constrained by a record that does not exist.
 * The constraint is therefore introspected from the compiler ({@link TriggerSemanticFacts}), never read
 * from the document — which is also what the governing DRY principle requires of an introspectable fact.
 *
 * @since 1.7.0
 */
final class ServiceAnnotationResolver {

    /** Spec §8 {@code attachPoint}: the single point this resolver owns. */
    static final String ATTACH_POINT_SERVICE = TriggerMetadataModel.Annotation.ATTACH_POINT_SERVICE;

    private ServiceAnnotationResolver() {
        // Prevent instantiation
    }

    /**
     * Resolves the annotations one service type must or may carry.
     *
     * <p>An entry that names no annotation is skipped outright — there is nothing to emit and nothing to
     * report a name for. An entry naming a <i>home-module</i> annotation the resolved package does not
     * declare is dropped and reported: a document authored against a different release must not put an
     * unresolvable name in the prompt, which is the guard {@link ServiceIdentityAspect} applies to a
     * service type for the same reason. A cross-module entry cannot be checked against this module's
     * symbols, so it is trusted rather than dropped.
     *
     * @param registry   the document's annotation registry
     * @param ids        the {@code serviceTypes[].annotations} ids this service type references; may be
     *                   {@code null}
     * @param homeModule spec §1's home module, which decides whether an entry is cross-module
     * @param facts      the resolved package's symbols, for the constraint and the existence check;
     *                   {@code null} skips both, so nothing is dropped for want of a compiled package
     * @return the references to emit and the entries dropped
     */
    static AnnotationScopeResolver.Resolution resolve(AnnotationRegistry registry, List<String> ids,
                                                      String homeModule, TriggerSemanticFacts facts) {
        // Selection is now the same by-id lookup every other attach point uses, so this component owns only
        // what is genuinely service-specific: the scope, and the two constants below.
        return AnnotationScopeResolver.byIds(registry, ids, AnnotationScopeResolver.Scope.SERVICE,
                homeModule, AnnotationScopeResolver.factsOf(facts));
    }

    /**
     * Spec §8 {@code presence}: {@code "required"} or {@code "optional"}. Anything else — including an
     * absent value — reads as optional, so an unrecognised vocabulary term cannot silently assert that
     * generated code is obliged to carry an annotation.
     *
     * @param annotation the registry entry
     * @return whether the annotation must be attached
     */
    static boolean isRequired(TriggerMetadataModel.Annotation annotation) {
        return annotation != null
                && TriggerMetadataModel.Annotation.PRESENCE_REQUIRED.equals(annotation.presence());
    }
}
