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

import io.ballerina.modelgenerator.commons.AnnotationAttachment;
import io.ballerina.modelgenerator.commons.FunctionData;
import io.ballerina.modelgenerator.commons.ServiceDatabaseManager;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Loads curated annotation descriptions from the service-index.sqlite database for Copilot.
 *
 * <p>The annotation catalog itself is built from the compiler Semantic Model, which is a superset
 * of the index (it covers every attachment point, not just SERVICE / OBJECT_METHOD). The index is
 * therefore consulted only for its hand-written {@code description}, which states what an
 * annotation is for and reads better than the package's own doc comment (compare the index's
 * "Define advanced configurations like service level security, etc." with {@code ballerina/http}'s
 * "The annotation which is used to configure an HTTP service.").</p>
 *
 * @since 1.7.0
 */
public final class AnnotationLoader {

    private static final Logger LOGGER = Logger.getLogger(AnnotationLoader.class.getName());

    private AnnotationLoader() {
        // Prevent instantiation
    }

    /**
     * Loads the curated descriptions for the given library's annotations, keyed on annotation name.
     * The attachment point is deliberately not part of the key: a description describes the
     * annotation, not one of its attachment points.
     *
     * @param libraryName the library name (e.g., "ballerinax/ftp")
     * @return annotation name to description; empty when the library has no index rows or on
     *         failure
     */
    public static Map<String, String> loadDescriptions(String libraryName) {
        Map<String, String> descriptions = new HashMap<>();

        String packageName = ServiceIndexLoader.stripOrg(libraryName);
        String org = libraryName.contains("/")
                ? libraryName.substring(0, libraryName.indexOf('/'))
                : "ballerinax";

        try {
            ServiceDatabaseManager db = ServiceDatabaseManager.getInstance();

            Optional<FunctionData> listenerOpt = db.getListener(org, packageName);
            if (listenerOpt.isEmpty()) {
                return descriptions;
            }
            int packageId = Integer.parseInt(listenerOpt.get().packageId());

            for (AnnotationAttachment attachment : db.getAnnotationAttachments(packageId)) {
                String annotName = attachment.annotName();
                String description = attachment.description();
                if (annotName == null || description == null || description.isEmpty()) {
                    continue;
                }
                descriptions.putIfAbsent(annotName, description);
            }
        } catch (RuntimeException e) {
            LOGGER.warning("Failed to load annotation descriptions from service-index for "
                    + libraryName + ": " + e.getMessage());
            return Map.of();
        }

        return descriptions;
    }
}
