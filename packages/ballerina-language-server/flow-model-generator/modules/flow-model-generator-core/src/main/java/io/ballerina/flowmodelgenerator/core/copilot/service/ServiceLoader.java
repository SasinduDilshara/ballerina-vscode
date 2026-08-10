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
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.ballerina.compiler.api.SemanticModel;
import io.ballerina.flowmodelgenerator.core.InstructionLoader;
import io.ballerina.projects.Package;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Service loader for loading library service definitions.
 * Loads trigger services from service-index.sqlite and generic services from generic-services.json.
 *
 * @since 1.7.0
 */
public class ServiceLoader {

    private static final Logger LOGGER = Logger.getLogger(ServiceLoader.class.getName());
    private static final String GENERIC_SERVICES_JSON_PATH = "/copilot/generic-services.json";
    /**
     * System property that forces the trigger-service source: {@code "index"} pins every library to
     * the SQLite service-index path; anything else (including unset) lets schema-driven libraries be
     * served from trigger metadata + the semantic model.
     */
    static final String TRIGGER_SOURCE_PROPERTY = "ballerina.copilot.triggerSource";

    // Lazily cached generic services keyed by library name
    private static volatile Map<String, JsonArray> genericServicesCache;

    private ServiceLoader() {
        // Prevent instantiation
    }

    /**
     * Loads all services for a given library from the service-index DB and generic services.
     * Index-sourced entries carry a {@code name} field (the service-type name); callers that
     * want deprecation flags should pass the result through
     * {@link CopilotDeprecationEnricher#enrich(JsonArray, io.ballerina.compiler.api.SemanticModel)}
     * before consuming.
     *
     * <p>If a generic-services.json entry shares its {@code name} with an index-sourced fixed
     * entry, the generic entry takes precedence and the fixed one is dropped. This lets curated
     * generic definitions (e.g. a hand-written {@code http:Listener} listener spec) override the
     * raw shape produced by the SQLite index.
     *
     * @param libraryName the library name (e.g., "ballerina/http", "ballerinax/kafka")
     * @return JsonArray containing all services for this library
     */
    public static JsonArray loadAllServices(String libraryName) {
        return mergeWithGenericServices(libraryName, ServiceIndexLoader.loadFromServiceIndex(libraryName),
                false);
    }

    /**
     * Loads all services for a library, preferring the schema-driven path (trigger metadata +
     * semantic model) whenever a metadata document resolves for the library, with an automatic
     * fallback to the SQLite service-index when it yields nothing. Setting the system property
     * {@value #TRIGGER_SOURCE_PROPERTY} to {@code "index"} pins everything to the SQLite path.
     *
     * <p>The schema path is attempted for every library, not a fixed set: {@code loadServices}
     * returns empty for anything with no metadata document, which is the overwhelming majority and
     * costs one {@code stat} against the already-resolved package. Falling through is therefore the
     * normal case and is not logged.
     *
     * @param libraryName   the library name (e.g., "ballerinax/kafka")
     * @param pkg           the resolved package the caller already compiled (may be null)
     * @param semanticModel the package's semantic model (may be null)
     * @return JsonArray containing all services for this library
     */
    public static JsonArray loadAllServices(String libraryName, Package pkg, SemanticModel semanticModel) {
        if (!"index".equals(System.getProperty(TRIGGER_SOURCE_PROPERTY))) {
            JsonArray schemaServices = TriggerSchemaServiceLoader.loadServices(libraryName, pkg, semanticModel);
            if (!schemaServices.isEmpty()) {
                return mergeWithGenericServices(libraryName, schemaServices, true);
            }
        }
        return loadAllServices(libraryName);
    }

    /**
     * Applies the generic-services overlay, and what a {@code name} collision means depends on where the
     * fixed entry came from.
     *
     * <p><b>Index-derived ({@code schemaDerived == false}) — replace.</b> The curated entry wins and the
     * index entry is dropped, exactly as before. An index row carries a listener and a method list and
     * nothing else; the curated prose was written precisely because that is too thin to generate against,
     * so there is nothing in it worth preserving alongside.
     *
     * <p><b>Schema-derived ({@code schemaDerived == true}) — <i>merge</i>.</b> The metadata-derived entry
     * survives and absorbs the curated guidance. This is the case that was silently destroying work:
     * {@code ballerina/http} and {@code ballerina/graphql} both declare {@code type.name = "Service"} and
     * both have a curated entry named {@code Service}, so their <b>entire trigger-metadata documents
     * rendered nothing at all</b> — for http, 8 method values, 3 path forms, 6 parameter slots, 7
     * annotation references (including the corpus's only {@code attachPoint: "return"} entry) and a
     * {@code dataBindingRules} rule; for graphql, three handler shapes including subscriptions, which the
     * curated prose never mentions.
     *
     * <p>The two sources are not substitutes and neither subsumes the other: the document states the
     * <i>facts</i> (types, presence, annotations, binding), while the curated file states the
     * <i>conventions</i> a document deliberately cannot carry — that an http listener belongs at module
     * level, that {@code @http:Payload} is optional for a lone record parameter, that a graphql service
     * defaults to {@code /graphql}. Merging keeps both; the old behaviour kept only the second.
     *
     * <p>The instruction text is loaded here rather than left to
     * {@code CopilotLibraryManager.augmentServicesWithInstructions}, which applies it only to entries typed
     * {@code generic}. Doing it at the point of absorption keeps the change surgical: no service that did
     * not previously carry curated guidance starts carrying it, so {@code ballerina/ai}'s never-yet-rendered
     * {@code service.md} stays exactly as unrendered as it is today.
     *
     * @param libraryName   the library being loaded
     * @param fixedServices the non-generic entries
     * @param schemaDerived whether {@code fixedServices} came from the trigger-metadata pipeline
     * @return the merged service list
     */
    private static JsonArray mergeWithGenericServices(String libraryName, JsonArray fixedServices,
                                                      boolean schemaDerived) {
        JsonArray genericServices = getGenericServices(libraryName);

        Set<String> genericNames = new HashSet<>();
        for (JsonElement element : genericServices) {
            JsonObject svc = element.getAsJsonObject();
            if (svc.has("name")) {
                genericNames.add(svc.get("name").getAsString());
            }
        }

        Set<String> absorbed = new HashSet<>();
        JsonArray services = new JsonArray();
        for (JsonElement element : fixedServices) {
            JsonObject svc = element.getAsJsonObject();
            String name = svc.has("name") ? svc.get("name").getAsString() : null;
            if (name != null && genericNames.contains(name)) {
                if (!schemaDerived) {
                    continue;
                }
                absorbed.add(name);
                InstructionLoader.loadServiceInstruction(libraryName)
                        .ifPresent(text -> svc.addProperty("instructions", text));
            }
            services.add(svc);
        }
        // Document order is preserved for whatever was not absorbed, so the index path emits exactly the
        // array it emitted before.
        for (JsonElement element : genericServices) {
            JsonObject svc = element.getAsJsonObject();
            if (svc.has("name") && absorbed.contains(svc.get("name").getAsString())) {
                continue;
            }
            services.add(svc);
        }
        return services;
    }

    /**
     * Returns cached generic services for a specific library from the generic-services.json resource.
     *
     * @param libraryName the library name (e.g., "ballerina/http")
     * @return JsonArray containing services for this library, or empty array if not found
     */
    private static JsonArray getGenericServices(String libraryName) {
        Map<String, JsonArray> cache = genericServicesCache;
        if (cache == null) {
            synchronized (ServiceLoader.class) {
                cache = genericServicesCache;
                if (cache == null) {
                    cache = loadGenericServicesMap();
                    genericServicesCache = cache;
                }
            }
        }
        return cache.getOrDefault(libraryName, new JsonArray());
    }

    /**
     * Parses generic-services.json once and indexes entries by library name.
     */
    private static Map<String, JsonArray> loadGenericServicesMap() {
        Map<String, JsonArray> map = new HashMap<>();

        try (InputStream inputStream = ServiceLoader.class.getResourceAsStream(GENERIC_SERVICES_JSON_PATH)) {
            if (inputStream == null) {
                LOGGER.warning("Generic services resource not found: " + GENERIC_SERVICES_JSON_PATH);
                return map;
            }

            try (InputStreamReader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
                JsonObject genericServicesData = JsonParser.parseReader(reader).getAsJsonObject();

                JsonArray allServices = genericServicesData.getAsJsonArray("services");
                if (allServices == null || allServices.isEmpty()) {
                    return map;
                }

                for (JsonElement serviceElement : allServices) {
                    JsonObject service = serviceElement.getAsJsonObject();

                    if (service.has("libraryName")) {
                        String libName = service.get("libraryName").getAsString();

                        JsonObject serviceObj = new JsonObject();
                        serviceObj.addProperty("type", service.get("type").getAsString());
                        if (service.has("name")) {
                            serviceObj.addProperty("name", service.get("name").getAsString());
                        }
                        serviceObj.addProperty("instructions", service.get("instructions").getAsString());

                        if (service.has("listener")) {
                            serviceObj.add("listener", service.get("listener"));
                        }

                        map.computeIfAbsent(libName, k -> new JsonArray()).add(serviceObj);
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.warning("Failed to load generic services: " + e.getMessage());
        }

        return map;
    }
}
