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
import io.ballerina.compiler.api.SemanticModel;
import io.ballerina.compiler.api.symbols.ClassSymbol;
import io.ballerina.compiler.api.symbols.ObjectTypeSymbol;
import io.ballerina.flowmodelgenerator.core.copilot.model.AnnotationAttachment;
import io.ballerina.modelgenerator.commons.ModuleInfo;
import io.ballerina.modelgenerator.commons.trigger.LibraryMetadataReader;
import io.ballerina.modelgenerator.commons.trigger.models.TriggerMetadataModel;
import io.ballerina.modelgenerator.commons.trigger.models.TypeRef;
import io.ballerina.projects.Package;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.logging.Logger;

/**
 * Schema-driven Copilot service loader: builds the Copilot's per-library {@code services} JSON from
 * exactly two read-only sources instead of the SQLite service-index —
 * <ol>
 *   <li><b>{@code trigger-metadata.json}</b> (the LS-bundled authoring metadata, resolved through
 *       {@link LibraryMetadataReader#getPackagedTriggerMetadataModel}): the structural truth — which
 *       service types exist, the handler vocabulary of marker types, parameter types/optionality,
 *       and return types;</li>
 *   <li><b>the semantic model</b> of the same resolved package the manager already compiles:
 *       listener class + init parameters (docs via the init method's {@code parameterMap()},
 *       declared defaults via the syntax tree), the declared methods <i>and doc comments</i> of
 *       concrete service types, and validation that every metadata claim (listener class,
 *       service-type names, handler signature types) actually exists in the resolved package
 *       version.</li>
 * </ol>
 * The trigger UI models ({@code trigger-models/*.json}) are deliberately <b>not</b> consumed: the
 * authoring metadata plus the library itself are the single source of truth for the Copilot.
 *
 * <p><b>FLAG — documentation gaps this leaves.</b> A marker service type (kafka/rabbitmq/ftp/smb/
 * websub/cdc {@code Service}) declares no methods in the library source — its handler contract is
 * enforced by a compiler plugin at user-code compile time — so there is no symbol carrying a doc
 * comment for a handler or its parameters, and {@code trigger-metadata.json} does not model
 * descriptions. Consequently <b>handler and handler-parameter descriptions are unavailable for
 * marker service types</b> and are simply omitted (never fabricated). Concrete service types are
 * unaffected: their declared methods' doc comments are read from the semantic model. Closing this
 * gap requires either optional {@code description} fields in the authoring schema or doc comments on
 * declared handler contracts in the connectors.
 *
 * <p><b>FLAG — handler parameter names are generated.</b> A handler parameter's name is chosen by
 * whoever writes the service, so the authoring schema intentionally omits it for such slots. Where
 * the metadata does state a name it always wins; otherwise a name is synthesized by
 * {@link HandlerParamNameGenerator} (handler parameters only — never listener init params, concrete
 * methods, client methods or type fields, all of which carry declared names).
 *
 * <p>A library is served here when a trigger metadata document exists for it, and stays on
 * {@link ServiceIndexLoader} otherwise — the set is discovered by looking the document up rather than
 * declared in code. The document is taken from the connector's own {@code .bala} when it ships one and
 * from the LS-bundled {@code trigger-metadata-models/<package>/} copy otherwise, so a library is
 * onboarded either by publishing its own document or by bundling one, with no change to this class. Output shape is exactly the Copilot service contract
 * ({@code type/name/listener/methods}), so downstream enrichers, the generic-services merge, and the
 * TS prompt renderer are untouched. Function-level {@code optional} is deliberately never emitted
 * (matching the previous output), and metadata constructs with no Copilot counterpart
 * ({@code dataBindingRules}, {@code identifier}) are ignored. A wildcard {@code "*"} handler and a
 * repeatable {@code addMode: "many"} parameter slot ARE emitted — each flagged so the renderer can say
 * what is open-ended — because they carry the document's annotation bindings and, for a marker service
 * type, the whole handler contract.

 * <p><b>Annotation bindings.</b> The document's {@code annotations} registry and its per-site
 * references are resolved by {@link TriggerAnnotationBinder} onto the service, its handlers and their
 * parameters. This is the only source for such a binding, for its {@code presence}, and for a
 * cross-module binding; the attachment body is generated from the annotation's constraint type via the
 * declaring module's Semantic Model.
 *
 * @since 1.7.0
 */
final class TriggerSchemaServiceLoader {

    private static final Logger LOGGER = Logger.getLogger(TriggerSchemaServiceLoader.class.getName());

    /**
     * Document keys that differ from the library's package name.
     *
     * <p>A bundled document is keyed by directory name under {@code trigger-metadata-models/}, which is
     * normally just the package name — so a new document is picked up with no code change at all
     * (see {@link #metadataKeys}). This map exists only for the cases where the trigger was published
     * under a different module than the package the user imports: {@code ballerinax/mssql} carries the
     * CDC trigger documented as {@code mssql.cdc}. Its listener ({@code CdcListener}) and handler set
     * are still validated against the actually resolved {@code mssql} package before use.</p>
     */
    private static final Map<String, String> METADATA_KEY_ALIASES = Map.of(
            "ballerinax/mssql", "mssql.cdc");

    private TriggerSchemaServiceLoader() {
        // Prevent instantiation
    }

    /**
     * The document keys to try for a library, in order: its package name, then any alias.
     *
     * <p>Deriving the key from the package name is what makes onboarding a library a data-only change:
     * drop {@code trigger-metadata-models/<package>/trigger-metadata.json} into the LS resources and it
     * is served, with no edit here.</p>
     */
    private static List<String> metadataKeys(String libraryName) {
        String packageName = ServiceIndexLoader.stripOrg(libraryName);
        String alias = METADATA_KEY_ALIASES.get(libraryName);
        return alias == null || alias.equals(packageName)
                ? List.of(packageName)
                : List.of(packageName, alias);
    }

    /**
     * Whether a trigger metadata document exists for this library, and it should therefore be served
     * from the document plus the semantic model rather than from the SQLite service-index.
     *
     * <p>Determined by looking the document up, not by an allow-list — so a newly bundled document is
     * honoured automatically. The lookup is a cached classpath read
     * ({@link LibraryMetadataReader#getPackagedTriggerMetadataModel}), so asking is cheap.</p>
     */
    static boolean isSchemaDriven(String libraryName, Package pkg) {
        return resolveMetadata(libraryName, pkg).isPresent();
    }

    /**
     * The trigger metadata document for a library: the connector's own copy first, then the LS-bundled
     * one, resolved through {@link LibraryMetadataReader#resolveTriggerMetadataModel(Path, ModuleInfo)}
     * — the same reader and the same precedence any other consumer can adopt.
     *
     * <p>The package root comes from the package the caller already resolved, so the connector's own
     * document costs one file check and nothing is looked up remotely. The bundled tier is tried under
     * each candidate key (see {@link #metadataKeys}).</p>
     */
    private static Optional<TriggerMetadataModel> resolveMetadata(String libraryName, Package pkg) {
        String packageName = ServiceIndexLoader.stripOrg(libraryName);
        String org = libraryName.contains("/")
                ? libraryName.substring(0, libraryName.indexOf('/'))
                : "ballerinax";
        Path packageRoot = packageRootOf(pkg);
        LibraryMetadataReader reader = LibraryMetadataReader.getInstance();
        for (String key : metadataKeys(libraryName)) {
            Optional<TriggerMetadataModel> metadata = reader.resolveTriggerMetadataModel(
                    packageRoot, new ModuleInfo(org, packageName, key, null));
            if (metadata.isPresent()) {
                return metadata;
            }
        }
        return Optional.empty();
    }

    /** The resolved package's source root, or {@code null} when there is no package to read from. */
    private static Path packageRootOf(Package pkg) {
        if (pkg == null) {
            return null;
        }
        try {
            return pkg.project().sourceRoot();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Loads services for a schema-driven library. Returns an empty array when the library is not
     * schema-driven, inputs are missing, the metadata document cannot be resolved, or anything
     * throws — the caller then falls back to the SQLite path, so a failure here can never lose a
     * library.
     */
    static JsonArray loadServices(String libraryName, Package pkg, SemanticModel semanticModel) {
        if (pkg == null || semanticModel == null) {
            return new JsonArray();
        }

        String packageName = ServiceIndexLoader.stripOrg(libraryName);
        String org = libraryName.contains("/")
                ? libraryName.substring(0, libraryName.indexOf('/'))
                : "ballerinax";

        try {
            Optional<TriggerMetadataModel> metadataOpt = resolveMetadata(libraryName, pkg);
            if (metadataOpt.isEmpty()) {
                return new JsonArray();
            }
            TriggerMetadataModel metadata = metadataOpt.get();
            if (metadata.listeners() == null || metadata.listeners().isEmpty()
                    || metadata.serviceTypes() == null || metadata.serviceTypes().isEmpty()) {
                return new JsonArray();
            }

            TriggerSemanticFacts facts = new TriggerSemanticFacts(semanticModel, pkg);

            // An unresolvable listener class means the resolved package no longer matches the
            // metadata's world view — hard-fail so the caller falls back to the SQLite path instead
            // of emitting a listener the generated code could not instantiate.
            String declaredListenerName = metadata.listeners().get(0).type() != null
                    ? metadata.listeners().get(0).type().name() : null;
            Optional<ClassSymbol> listenerClass = facts.resolveListenerClass(declaredListenerName);
            if (listenerClass.isEmpty()) {
                LOGGER.warning("No listener class resolvable for " + libraryName
                        + " (metadata declared: " + declaredListenerName + ")");
                return new JsonArray();
            }

            JsonObject listenerJson = buildListener(listenerClass.get(), facts, packageName);

            // Resolves the document's annotation bindings — the one kind of annotation information no
            // Semantic Model can produce for a marker service type.
            TriggerAnnotationBinder binder =
                    new TriggerAnnotationBinder(metadata, semanticModel, org, packageName);

            JsonArray services = new JsonArray();
            for (TriggerMetadataModel.ServiceType serviceType : metadata.serviceTypes()) {
                String typeName = serviceType.type() != null ? serviceType.type().name() : null;
                if (typeName == null) {
                    continue;
                }
                // A same-module service type must exist in the resolved package version (guards
                // against metadata authored for a future release); a cross-module type (e.g. mssql's
                // cdc:Service) cannot be checked against this module's symbols and is trusted.
                boolean foreignType = serviceType.type().packageInfo() != null
                        && serviceType.type().packageInfo().packageName() != null
                        && !serviceType.type().packageInfo().packageName().equals(packageName);
                if (!foreignType && !facts.declaresType(typeName)) {
                    LOGGER.warning("Skipping service type " + typeName + " for " + libraryName
                            + ": not declared by the resolved package version");
                    continue;
                }

                // A concrete service type whose object type cannot be introspected would emit a
                // phantom method-less service — skip it (a fully skipped library then falls back).
                TriggerMetadataModel.ServiceType.Handlers handlers = serviceType.handlers();
                boolean concrete = serviceType.concrete() || handlers == null
                        || handlers.backedByConcreteType();
                if (concrete && facts.serviceObjectType(typeName).isEmpty()) {
                    LOGGER.warning("Skipping concrete service type " + typeName + " for " + libraryName
                            + ": no introspectable service object type");
                    continue;
                }

                JsonObject svc = new JsonObject();
                svc.addProperty("type", "fixed");
                // Note: for a cross-module service type (mssql's cdc:Service) this is the bare type
                // name; CopilotDeprecationEnricher's lookup against this module's symbols is then a
                // deliberate no-op unless the module declares the same name itself.
                svc.addProperty("name", typeName);
                svc.add("listener", listenerJson);

                JsonArray serviceAnnotations = binder.forServiceType(serviceType.id());
                if (!serviceAnnotations.isEmpty()) {
                    svc.add("annotations", serviceAnnotations);
                }

                JsonArray methods = concrete
                        ? buildConcreteMethods(typeName, facts, packageName, org, handlers, binder)
                        : buildOptionMethods(handlers.options(), typeName, facts::declaresType,
                                packageName, binder);
                if (!methods.isEmpty()) {
                    svc.add("methods", methods);
                }
                services.add(svc);
            }
            return services;
        } catch (RuntimeException e) {
            LOGGER.warning("Failed to load schema-driven services for " + libraryName + ": " + e.getMessage());
            return new JsonArray();
        }
    }

    // ---- listener --------------------------------------------------------------

    private static JsonObject buildListener(ClassSymbol listenerClass, TriggerSemanticFacts facts,
                                            String packageName) {
        String className = listenerClass.getName().orElse("Listener");

        JsonObject listenerObj = new JsonObject();
        listenerObj.addProperty("name", getAlias(packageName) + ":" + className);

        JsonArray parameters = new JsonArray();
        for (TriggerSemanticFacts.InitParam param : facts.listenerInitParams(listenerClass)) {
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

    // ---- methods ---------------------------------------------------------------

    /**
     * Concrete service types: the type declares its own methods, so everything — names, parameter
     * names, types, and doc comments — comes from the semantic model. Nothing is generated here.
     *
     * <p>FLAG: when the library ships no doc comments on a declared handler (e.g.
     * {@code trigger.github}'s event methods), the {@code description} key is simply omitted; no
     * text is invented.
     */
    private static JsonArray buildConcreteMethods(String typeName, TriggerSemanticFacts facts,
                                                  String packageName, String org,
                                                  TriggerMetadataModel.ServiceType.Handlers handlers,
                                                  TriggerAnnotationBinder binder) {
        // A concrete type reads its methods from the symbol, but the document may still bind
        // annotations to them by name; honour those exactly as the marker path does.
        Map<String, TriggerMetadataModel.ServiceType.HandlerOption> optionsByName = new HashMap<>();
        if (handlers != null && handlers.options() != null) {
            for (TriggerMetadataModel.ServiceType.HandlerOption option : handlers.options()) {
                if (option != null && option.name() != null) {
                    optionsByName.put(option.name(), option);
                }
            }
        }
        JsonArray methods = new JsonArray();
        Optional<ObjectTypeSymbol> objectType = facts.serviceObjectType(typeName);
        if (objectType.isEmpty()) {
            return methods;
        }
        for (TriggerSemanticFacts.DeclaredMethod declared
                : facts.declaredMethods(objectType.get(), org, packageName)) {
            JsonObject method = new JsonObject();
            method.addProperty("name", declared.name());
            method.addProperty("type", declared.kind());

            if (declared.description() != null && !declared.description().isEmpty()) {
                method.addProperty("description", declared.description());
            }
            // Attachments the declared method actually carries: only a concrete service type has a
            // method symbol to read them from. The document's bindings for the same handler are
            // merged in, so a concrete type is not treated differently from a marker one.
            addAttachments(method, declared.annotations(),
                    binder == null ? null : binder.forIds(bindingIds(optionsByName.get(declared.name()))));

            if (!declared.params().isEmpty()) {
                JsonArray params = new JsonArray();
                TriggerMetadataModel.ServiceType.HandlerOption option =
                        optionsByName.get(declared.name());
                List<TriggerMetadataModel.ServiceType.Param> documentParams =
                        option == null ? null : option.params();
                for (int i = 0; i < declared.params().size(); i++) {
                    TriggerSemanticFacts.DeclaredParam param = declared.params().get(i);
                    JsonObject paramJson = buildParam(param.name(), param.description(),
                            param.typeSignature(), param.optional(), packageName);
                    // Positional match against the document's parameter list, which is authored in
                    // declaration order alongside the same signature.
                    List<String> documentBindingIds = documentParams != null && i < documentParams.size()
                            && documentParams.get(i) != null
                            ? documentParams.get(i).annotations() : null;
                    addAttachments(paramJson, param.annotations(),
                            binder == null ? null : binder.forIds(documentBindingIds));
                    params.add(paramJson);
                }
                method.add("parameters", params);
            }

            addReturn(method, declared.returnTypeSignature(), packageName);
            methods.add(method);
        }
        return methods;
    }

    /**
     * Adds an {@code annotations} array combining the attachments a symbol actually carries with the
     * document's bindings for the same site, de-duplicated by {@code module + name} so a binding the
     * library already writes itself is not repeated. Adds nothing when both are empty.
     */
    private static void addAttachments(JsonObject target, List<AnnotationAttachment> attachments,
                                       JsonArray documentBindings) {
        JsonArray array = new JsonArray();
        Set<String> seen = new HashSet<>();
        if (attachments != null) {
            for (AnnotationAttachment attachment : attachments) {
                if (!seen.add(attachment.getModule() + "::" + attachment.getName())) {
                    continue;
                }
                JsonObject json = new JsonObject();
                json.addProperty("name", attachment.getName());
                if (attachment.getModule() != null) {
                    json.addProperty("module", attachment.getModule());
                }
                if (attachment.getValue() != null) {
                    json.addProperty("value", attachment.getValue());
                }
                array.add(json);
            }
        }
        if (documentBindings != null) {
            for (JsonElement element : documentBindings) {
                JsonObject binding = element.getAsJsonObject();
                String module = binding.has("module") ? binding.get("module").getAsString() : null;
                if (seen.add(module + "::" + binding.get("name").getAsString())) {
                    array.add(binding);
                }
            }
        }
        if (!array.isEmpty()) {
            target.add("annotations", array);
        }
    }

    /** The annotation ids a handler option binds, or {@code null} when there is no option. */
    private static List<String> bindingIds(TriggerMetadataModel.ServiceType.HandlerOption option) {
        return option == null ? null : option.annotations();
    }

    /**
     * Marker service types: the type declares no methods, so the handler vocabulary, parameter types,
     * optionality and returns all come from the metadata document.
     *
     * <p>FLAG — two things the metadata document cannot supply here, by design:
     * <ul>
     *   <li><b>descriptions</b> — neither the document (no {@code description} field) nor the library
     *       (no declared method to document) has them, so handler and parameter {@code description}
     *       keys are omitted rather than fabricated;</li>
     *   <li><b>parameter names</b> — a handler parameter's name is the service author's choice, so
     *       the document states it only where a conventional name exists. Where it does, it wins;
     *       otherwise {@link HandlerParamNameGenerator} synthesizes a deterministic, idiomatic one.</li>
     * </ul>
     *
     * <p>A <b>wildcard</b> handler ({@code name: "*"}) is emitted, not skipped: it is how a document
     * states that the service author declares the methods and chooses their names, and for such a
     * service type it is the <em>only</em> handler entry — dropping it would lose the whole handler
     * contract along with the annotations bound to it. Its name is a generated placeholder and the
     * entry is flagged {@code nameIsUserDefined} so the renderer can say so. Likewise a repeatable
     * ({@code addMode: "many"}) parameter slot is emitted once, flagged {@code repeatable}, since the
     * slot's type and its annotation binding are real even though the count is open-ended.</p>
     */
    static JsonArray buildOptionMethods(List<TriggerMetadataModel.ServiceType.HandlerOption> options,
                                        String typeName, Predicate<String> declaresType,
                                        String packageName, TriggerAnnotationBinder binder) {
        JsonArray methods = new JsonArray();
        if (options == null) {
            return methods;
        }
        for (TriggerMetadataModel.ServiceType.HandlerOption option : options) {
            if (option == null || option.name() == null) {
                continue;
            }
            boolean wildcard =
                    TriggerMetadataModel.ServiceType.HandlerOption.WILDCARD_NAME.equals(option.name());
            // Same validation philosophy as service types: a handler whose signature references a
            // same-module type the resolved package does not declare (metadata authored against a
            // different/future release) would render an uncompilable prompt — skip it.
            if (referencesUndeclaredModuleType(option, declaresType)) {
                LOGGER.warning("Skipping handler " + option.name() + " of " + typeName
                        + ": its signature references a type the resolved package does not declare");
                continue;
            }
            JsonObject method = new JsonObject();
            method.addProperty("name", wildcard ? placeholderHandlerName(option) : option.name());
            method.addProperty("type",
                    TriggerMetadataModel.ServiceType.HandlerOption.KIND_RESOURCE.equals(option.kind())
                            ? "resource" : "remote");
            if (wildcard) {
                method.addProperty("nameIsUserDefined", true);
            }
            // No description key: see the FLAG in this method's javadoc.
            if (binder != null) {
                JsonArray handlerAnnotations = binder.forIds(option.annotations());
                if (!handlerAnnotations.isEmpty()) {
                    method.add("annotations", handlerAnnotations);
                }
            }

            List<TriggerMetadataModel.ServiceType.Param> optionParams = option.params();
            if (optionParams != null && !optionParams.isEmpty()) {
                JsonArray params = new JsonArray();
                Set<String> usedNames = new HashSet<>();
                for (TriggerMetadataModel.ServiceType.Param p : optionParams) {
                    if (p != null && p.name() != null) {
                        usedNames.add(p.name());
                    }
                }
                for (int i = 0; i < optionParams.size(); i++) {
                    TriggerMetadataModel.ServiceType.Param param = optionParams.get(i);
                    if (param == null) {
                        continue;
                    }
                    boolean repeatable = TriggerMetadataModel.ServiceType.Handlers.ADD_MODE_MANY
                            .equals(param.addMode());
                    // The authored name wins; otherwise generate one (handler params only).
                    String name = param.name() != null ? param.name()
                            : HandlerParamNameGenerator.generate(firstTypeRef(param.type()),
                                    param.dataBinding() != null, getAlias(packageName), i, usedNames);
                    usedNames.add(name);
                    String typeSignature = renderTypeRef(firstTypeRef(param.type()), packageName,
                            declaresType);
                    boolean optional = "optional".equals(param.presence());

                    // No description argument: see the FLAG in this method's javadoc.
                    JsonObject paramJson = buildParam(name, null, typeSignature, optional, packageName);
                    if (repeatable) {
                        paramJson.addProperty("repeatable", true);
                    }
                    if (binder != null) {
                        JsonArray paramAnnotations = binder.forIds(param.annotations());
                        if (!paramAnnotations.isEmpty()) {
                            paramJson.add("annotations", paramAnnotations);
                        }
                    }
                    params.add(paramJson);
                }
                if (!params.isEmpty()) {
                    method.add("parameters", params);
                }
            }

            addReturn(method, renderReturns(option.returns(), packageName, declaresType), packageName);
            methods.add(method);
        }
        return methods;
    }

    // ---- shared building blocks --------------------------------------------------

    private static JsonObject buildParam(String name, String description, String typeSignature,
                                         boolean optional, String packageName) {
        JsonObject paramObj = new JsonObject();
        paramObj.addProperty("name", name);
        if (description != null && !description.isEmpty()) {
            paramObj.addProperty("description", description);
        }
        paramObj.add("type", TypeResolver.resolveTypeWithLinks(
                typeSignature != null ? typeSignature : "", packageName));
        if (optional) {
            paramObj.addProperty("optional", true);
        }
        return paramObj;
    }

    /**
     * Adds the {@code return} object unless the (canonicalized) signature is empty or plain
     * {@code ()} — a nil return carries no information and today's output omits it.
     */
    private static void addReturn(JsonObject method, String returnSignature, String packageName) {
        if (returnSignature == null || returnSignature.isEmpty()) {
            return;
        }
        String canonical = ServiceIndexLoader.canonicalizeReturnType(returnSignature);
        if (canonical.isEmpty() || "()".equals(canonical)) {
            return;
        }
        JsonObject returnObj = new JsonObject();
        returnObj.add("type", TypeResolver.resolveTypeWithLinks(canonical, packageName));
        method.add("return", returnObj);
    }

    /**
     * A placeholder identifier for a wildcard handler, whose real name the service author chooses.
     * Derived from the document itself — the id of the first annotation bound to the handler, which is
     * what the handler <em>is</em> in the connector's vocabulary (a {@code tool} binding yields
     * {@code toolName}) — falling back to a neutral name when the document binds none. No library is
     * named here: the input is whatever id the document happens to carry.
     */
    static String placeholderHandlerName(TriggerMetadataModel.ServiceType.HandlerOption option) {
        String base = null;
        List<String> annotationIds = option.annotations();
        if (annotationIds != null) {
            for (String id : annotationIds) {
                if (id != null && !id.isEmpty()) {
                    base = id;
                    break;
                }
            }
        }
        if (base == null) {
            return "handlerName";
        }
        String candidate = Character.toLowerCase(base.charAt(0)) + base.substring(1) + "Name";
        return HandlerParamNameGenerator.isReserved(candidate) ? "handlerName" : candidate;
    }

    /** The codegen-default member: the first element of a scalar-or-union {@code TypeRef} slot. */
    static TypeRef firstTypeRef(List<TypeRef> refs) {
        return refs == null || refs.isEmpty() ? null : refs.get(0);
    }

    /**
     * Renders a metadata {@code TypeRef} into the module-prefixed signature form the service-index
     * stored, so the shared {@link TypeResolver} produces identical {@code {name, links}} output:
     * a cross-module ref gets its own module's alias prefix (and, since the prefix won't match the
     * current package, no link — e.g. {@code cdc:Error}); a same-module declared type gets the
     * current alias prefix (stripped back off with a link); built-ins and anonymous shapes stay bare.
     */
    static String renderTypeRef(TypeRef ref, String packageName, Predicate<String> declaresType) {
        if (ref == null || ref.name() == null) {
            return "";
        }
        String name = ref.name();
        if (ref.packageInfo() != null) {
            String refPackage = ref.packageInfo().packageName();
            String refModule = ref.packageInfo().moduleName() != null
                    ? ref.packageInfo().moduleName() : refPackage;
            if (refPackage != null && !refPackage.equals(packageName)) {
                return getAlias(refModule) + ":" + name;
            }
            return getAlias(packageName) + ":" + name;
        }
        String base = baseIdentifier(name);
        if (base != null && declaresType.test(base)) {
            return getAlias(packageName) + ":" + name;
        }
        return name;
    }

    /**
     * Joins a metadata {@code returns} union into a single signature ({@code error|()}) ready for
     * {@link ServiceIndexLoader#canonicalizeReturnType}.
     */
    static String renderReturns(List<TypeRef> returns, String packageName, Predicate<String> declaresType) {
        if (returns == null || returns.isEmpty()) {
            return "";
        }
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < returns.size(); i++) {
            if (i > 0) {
                joined.append("|");
            }
            joined.append(renderTypeRef(returns.get(i), packageName, declaresType));
        }
        return joined.toString();
    }

    /**
     * Whether the handler's emitted signature (first type member of each parameter, every return
     * member) references a bare, capitalized — i.e. user-defined-looking — same-module type the
     * resolved package does not declare.
     */
    static boolean referencesUndeclaredModuleType(TriggerMetadataModel.ServiceType.HandlerOption option,
                                                  Predicate<String> declaresType) {
        if (option.params() != null) {
            for (TriggerMetadataModel.ServiceType.Param param : option.params()) {
                if (param != null && isUndeclaredBareUserType(firstTypeRef(param.type()), declaresType)) {
                    return true;
                }
            }
        }
        if (option.returns() != null) {
            for (TypeRef ref : option.returns()) {
                if (isUndeclaredBareUserType(ref, declaresType)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isUndeclaredBareUserType(TypeRef ref, Predicate<String> declaresType) {
        if (ref == null || ref.name() == null || ref.packageInfo() != null) {
            return false;
        }
        String base = baseIdentifier(ref.name());
        return base != null && !base.isEmpty() && Character.isUpperCase(base.charAt(0))
                && !declaresType.test(base);
    }

    /** Leading identifier of a type name: {@code "AnydataConsumerRecord[]"} → {@code "AnydataConsumerRecord"}. */
    static String baseIdentifier(String typeName) {
        if (typeName == null || typeName.isEmpty()) {
            return null;
        }
        int end = 0;
        while (end < typeName.length()
                && (Character.isLetterOrDigit(typeName.charAt(end)) || typeName.charAt(end) == '_')) {
            end++;
        }
        return end == 0 ? null : typeName.substring(0, end);
    }

    static String getAlias(String moduleName) {
        if (moduleName != null && moduleName.contains(".")) {
            return moduleName.substring(moduleName.lastIndexOf('.') + 1);
        }
        return moduleName;
    }
}
