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

import java.util.List;

/**
 * Spec §2.1 {@code listeners[].platformDependencies} — native artifacts the build cannot fetch.
 *
 * <p>Carried on the <b>service</b> rather than hoisted to the library, for the same reason
 * {@link RequiredImportAspect} is: the spec declares these on the listener, so only code that actually uses
 * that listener needs them.
 *
 * @since 1.10.0
 */
final class PlatformDependencyAspect implements ServiceAspect {

    @Override
    public String id() {
        return "platformDependencies";
    }

    @Override
    public String specSection() {
        return "§2.1";
    }

    @Override
    public void contribute(TriggerScope scope, ServiceDraft draft) {
        List<PlatformDependencyResolver.PlatformDependency> dependencies =
                PlatformDependencyResolver.resolve(scope.listener());
        if (dependencies.isEmpty()) {
            return;
        }
        JsonArray json = new JsonArray();
        for (PlatformDependencyResolver.PlatformDependency dependency : dependencies) {
            JsonObject entry = new JsonObject();
            entry.addProperty("coordinate", dependency.coordinate());
            if (dependency.provided()) {
                // Emitted only when true, per the omission rule: absent means bundled, which is the case
                // that needs no action from the reader.
                entry.addProperty("provided", true);
            }
            if (dependency.acquisitionUrl() != null) {
                entry.addProperty("acquisitionUrl", dependency.acquisitionUrl());
            }
            if (dependency.acquisitionNote() != null) {
                entry.addProperty("acquisitionNote", dependency.acquisitionNote());
            }
            if (!dependency.nativeLibraries().isEmpty()) {
                JsonArray libraries = new JsonArray();
                for (PlatformDependencyResolver.NativeLibrary library : dependency.nativeLibraries()) {
                    JsonObject entryJson = new JsonObject();
                    entryJson.addProperty("os", library.os());
                    entryJson.addProperty("file", library.file());
                    if (library.variable() != null) {
                        entryJson.addProperty("variable", library.variable());
                    }
                    libraries.add(entryJson);
                }
                entry.add("nativeLibraries", libraries);
            }
            json.add(entry);
        }
        draft.setPlatformDependencies(json);
    }
}
