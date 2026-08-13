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

/**
 * Spec §7 {@code params[].presence} — whether the slot may be omitted from the signature.
 *
 * <p>Reads whichever source the handler came from: a metadata slot states {@code presence}, a concrete
 * service type's declared parameter carries the compiler's answer. Order-independent within the parameter
 * tier — {@link ParamDraft} holds the flag in its own slot and emits the wire contract's key order itself.
 *
 * @since 1.10.0
 */
final class ParamPresenceAspect implements ParamAspect {

    @Override
    public String id() {
        return "paramPresence";
    }

    @Override
    public String specSection() {
        return "§7";
    }

    @Override
    public void contribute(ParamScope scope, ParamDraft draft) {
        draft.setOptional(scope.declared() != null
                ? ParamPresenceResolver.isOptional(scope.declared())
                : ParamPresenceResolver.isOptional(scope.param()));
    }
}
