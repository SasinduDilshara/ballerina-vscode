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
import io.ballerina.compiler.api.symbols.ClassSymbol;
import io.ballerina.modelgenerator.commons.trigger.utils.TypeRefResolver;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Spec §2 {@code listeners[].type} — the listener a service attaches to, with its init parameters.
 *
 * <p>Spec §2 is explicit that "No listener init fields are ever modeled" in the document, so every
 * parameter here comes from the semantic model: names and types from the {@code init} signature,
 * descriptions from its doc comment, and declared defaults recovered from the syntax tree.
 *
 * <p>The built object is <b>cached and shared</b> by identity across every service entry of a library,
 * exactly as before. That sharing is load-bearing rather than incidental: a downstream enricher rewrites
 * {@code listener.name} in place for packages shipping a non-canonical listener class, and handing each
 * service its own copy would change how many times that rewrite is applied.
 *
 * <p>The cache is keyed on the <b>document's</b> listener entry, falling back to the class only when there
 * is none. Keying on the class alone was safe while every field came from the semantic model, but §2's
 * {@code deprecated} is authored per listener entry — and two entries may name one class, in which case a
 * class-keyed cache would hand the second entry the first's deprecation note. One object per document
 * listener still preserves the sharing the rewrite depends on.
 *
 * @since 1.7.0
 */
final class ListenerAspect implements ServiceAspect {

    private static final String DEFAULT_LISTENER_NAME = "Listener";

    private final Map<Object, JsonObject> built = new IdentityHashMap<>();

    @Override
    public String id() {
        return "listener";
    }

    @Override
    public String specSection() {
        return "§2";
    }

    @Override
    public void contribute(TriggerScope scope, ServiceDraft draft) {
        Object key = scope.listener() != null ? scope.listener() : scope.listenerClass();
        draft.setListener(built.computeIfAbsent(key, unused -> build(scope)));
        // Spec §2 `services`, the other half of what this aspect owns: the listener object says *how* to
        // construct the listener, this says whether this service type may be attached to one at all.
        // The listener is still emitted either way — a consumer needs its types even when the service is
        // written some other way, and the type closure reaches them through it.
        if (scope.document() != null
                && !ListenerPairingResolver.isHostedByAnyListener(
                        scope.document().listeners(), scope.serviceType())) {
            draft.setNotListenerAttachable();
        }
    }

    private static JsonObject build(TriggerScope scope) {
        ClassSymbol listenerClass = scope.listenerClass();
        String packageName = scope.packageName();
        String className = listenerClass.getName().orElse(DEFAULT_LISTENER_NAME);

        JsonObject listenerObj = new JsonObject();
        listenerObj.addProperty("name", TypeRefResolver.moduleAlias(packageName) + ":" + className);
        // Spec §2 `deprecated`: prose, not a flag. The document says *why* the listener is superseded, and
        // that sentence is the only thing that tells a reader what to use instead — a bare `@deprecated`
        // would leave them with a warning and no alternative.
        if (scope.listener() != null && scope.listener().deprecated() != null
                && !scope.listener().deprecated().isBlank()) {
            listenerObj.addProperty("deprecated", scope.listener().deprecated());
        }

        JsonArray parameters = new JsonArray();
        for (TriggerSemanticFacts.InitParam param : scope.facts().listenerInitParams(listenerClass)) {
            JsonObject paramObj = new JsonObject();
            paramObj.addProperty("name", param.name());
            paramObj.addProperty("description", param.description() != null ? param.description() : "");
            paramObj.add("type", TypeResolver.resolveTypeWithLinks(
                    param.typeSignature() != null ? param.typeSignature() : "", packageName));
            if (param.optional()) {
                paramObj.addProperty("optional", true);
            }
            if (param.defaultValue() != null && !param.defaultValue().isEmpty()) {
                paramObj.addProperty("default", param.defaultValue());
            }
            parameters.add(paramObj);
        }
        listenerObj.add("parameters", parameters);
        return listenerObj;
    }
}
