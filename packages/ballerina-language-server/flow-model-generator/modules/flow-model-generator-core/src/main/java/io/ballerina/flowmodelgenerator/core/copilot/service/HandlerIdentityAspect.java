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

/**
 * Spec §5 {@code options[].name} — a handler's name, and its description where one exists.
 *
 * <p>The two provenances differ in what they can supply. A concrete type's declared method carries a
 * real name and doc comment. A marker type's handler has only what the document states, and the
 * document models no descriptions — so no {@code description} is emitted for one, rather than inventing
 * text.
 *
 * <p>{@code kind} used to be set here too. It moved to {@link HandlerKindAspect}, because it is the field
 * the renderer's keyword choice depends on and it drags a resource handler's accessor along with it —
 * neither of which has anything to do with naming.
 *
 * @since 1.7.0
 */
final class HandlerIdentityAspect implements HandlerAspect {

    @Override
    public String id() {
        return "handlerIdentity";
    }

    @Override
    public String specSection() {
        return "§5";
    }

    @Override
    public void contribute(HandlerScope scope, HandlerDraft draft) {
        if (scope.isConcrete()) {
            TriggerSemanticFacts.DeclaredMethod declared = scope.declared();
            draft.setName(declared.name());
            draft.setDescription(declared.description());
            return;
        }
        TriggerMetadataModel.ServiceType.HandlerOption option = scope.option();
        draft.setName(option.name());
        // Spec §5.1 inverts the DRY rule here, and only here. A marker service type declares no method, so
        // no symbol carries a doc comment for its handlers — which is why this used to emit no description
        // at all. The document now authors one, and it is the ONLY description a generator will ever see
        // for such a handler.
        draft.setDescription(option.doc());
        // Spec §5.3 `deprecated`: prose, and its own doc section rather than a flag. `ftp`'s `onFileChange`
        // is the corpus instance, and its sentence names the five typed handlers that replace it — the
        // whole point of the field. A boolean would tell a reader to stop using the handler without saying
        // what to use instead, which is the one thing they need.
        draft.setDeprecated(option.deprecated());
    }
}
