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
 * Owns <b>the spec {@code params[].addMode}</b>: whether a parameter slot repeats.
 *
 * <p>The spec defines exactly one value — {@code "many"}, meaning the slot repeats zero or more times, each
 * occurrence independently named and typed (HTTP query/header, MCP tool args) — with an absent key meaning
 * "at most one".
 *
 * <p><b>Replaces {@code ParamTypeResolver.isRepeatable}</b>, which owned this key while
 * {@code ParamTypeResolver} otherwise owns {@code type}/{@code presence}/{@code name}. The split matters
 * because the renderer treats the two modifiers completely differently: {@code presence} keeps a slot in the
 * signature and adds a note, whereas {@code addMode} takes it <i>out</i> of the signature altogether.
 *
 * <p><b>A repeatable slot is not a parameter.</b> Skipping it outright lost everything the document said
 * about it, but emitting it as a parameter would be worse: the document states no name, so a generated
 * {@code anydata param2} would be an invented parameter in a signature meant to be copied. The slot is
 * carried to the wire, marked, and rendered as a note rather than as syntax.
 *
 * @since 1.7.0
 */
final class ParamRepeatResolver {

    private ParamRepeatResolver() {
        // Prevent instantiation
    }

    /**
     * Whether a slot repeats.
     *
     * <p>Only the declared {@code "many"} is recognised. An unrecognised term reads as "at most one",
     * which is the spec's stated default for an absent key: mis-reading an unknown term as repeatable would
     * silently delete a real parameter from the signature.
     *
     * @param addMode the slot's declared {@code addMode}; may be {@code null}
     * @return whether the slot repeats zero or more times
     */
    static boolean isRepeatable(String addMode) {
        return TriggerMetadataModel.ServiceType.HandlerOption.ADD_MODE_MANY.equals(addMode);
    }

    /**
     * {@link #isRepeatable(String)} for a whole slot, so callers holding a {@code Param} do not each
     * reach into it for the same field.
     *
     * @param param the slot; may be {@code null}
     * @return whether the slot repeats zero or more times
     */
    static boolean isRepeatable(TriggerMetadataModel.ServiceType.Param param) {
        return param != null && isRepeatable(param.addMode());
    }
}
