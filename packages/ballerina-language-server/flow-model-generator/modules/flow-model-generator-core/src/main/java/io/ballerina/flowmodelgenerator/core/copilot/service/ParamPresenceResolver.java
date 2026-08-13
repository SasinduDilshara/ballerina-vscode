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
 * Owns <b>spec §7 {@code params[].presence}</b>: whether a handler parameter may be omitted.
 *
 * <p>Its own component rather than a method on {@link ParamTypeResolver}, for the same reason
 * {@link ParamRepeatResolver} was split out of it: one spec key, one owner. A parameter slot carries three
 * independent modifiers — its type surface (§7 {@code type}), whether it may be omitted ({@code presence}),
 * and whether it repeats ({@code addMode}) — and the consumer treats all three differently. While
 * {@code presence} lived inside the type resolver, a change to what §7 says about optionality had two
 * plausible homes, which is exactly the ambiguity the one-owner rule exists to remove.
 *
 * <p><b>Both sources answer here.</b> A metadata slot states {@code presence}; a concrete service type's
 * declared parameter answers from the semantic model instead. Keeping both in one component means "is this
 * slot optional?" has a single answer no matter where the handler came from.
 *
 * @since 1.10.0
 */
final class ParamPresenceResolver {

    /**
     * Spec §10's presence vocabulary. Declared here rather than borrowed from a sibling construct's
     * constant: {@code params[].presence} is its own slot, and coupling it to an unrelated type's
     * constant would make a future divergence in either invisible.
     */
    private static final String PRESENCE_OPTIONAL = "optional";

    private ParamPresenceResolver() {
        // Prevent instantiation
    }

    /**
     * Spec §7 {@code presence}: {@code "optional"} is the only value that changes the signature.
     *
     * <p>Anything else — {@code "required"}, an unrecognised token, or an absent key — reads as required.
     * That asymmetry is deliberate: a parameter wrongly marked optional invites the reader to omit
     * something the handler contract needs, whereas one wrongly marked required only costs an argument
     * that could have been left out.
     *
     * @param param the documented slot
     * @return whether the slot may be omitted from the signature
     */
    static boolean isOptional(TriggerMetadataModel.ServiceType.Param param) {
        return param != null && PRESENCE_OPTIONAL.equals(param.presence());
    }

    /**
     * The same question for a concrete service type's declared parameter, where the compiler has already
     * answered it: a defaultable or included-record parameter is the one a caller may leave out.
     *
     * @param declared the parameter introspected from the resolved package
     * @return whether the slot may be omitted from the signature
     */
    static boolean isOptional(TriggerSemanticFacts.DeclaredParam declared) {
        return declared != null && declared.optional();
    }
}
