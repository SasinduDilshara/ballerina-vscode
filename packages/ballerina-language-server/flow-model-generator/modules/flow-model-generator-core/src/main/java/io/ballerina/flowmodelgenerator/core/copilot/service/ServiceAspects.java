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
import io.ballerina.modelgenerator.commons.trigger.models.PresenceForm;
import io.ballerina.modelgenerator.commons.trigger.utils.TypeRefResolver;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The service tier: everything stated once per service entry. Spec §6 constraints are
 * {@link ConstraintAspect}, the handler catalog is {@link HandlerCatalogAspect}, and §8 service annotations
 * are {@link AnnotationAspects#service}; all three are large enough to own their own file.
 *
 * <p><b>Instance, not static.</b> {@link #listener} memoizes the object it builds for the lifetime of one
 * library load, so an instance is created per {@link AspectRegistry} and never shared across libraries.
 *
 * @since 1.7.0
 */
final class ServiceAspects {

    /** The wire contract's discriminator; every metadata-derived entry is a fixed-shape service. */
    private static final String KIND_FIXED = "fixed";

    private static final String DEFAULT_LISTENER_NAME = "Listener";

    private final Map<Object, JsonObject> builtListeners = new IdentityHashMap<>();

    /**
     * Spec §1/§3 — the service entry's identity: its type name and, when cross-module, the module that
     * owns it.
     *
     * <p>Runs first among the service aspects, because it is also the component that can veto the entry
     * outright: a home-module service type the resolved package does not declare would render a service on
     * a type that does not exist in the version actually resolved.
     */
    void identity(TriggerScope scope, ServiceDraft draft) {
        ServiceIdentityResolver.ServiceIdentity identity = ServiceIdentityResolver.resolve(
                scope.serviceType(), scope.homeModule(), scope.declaresType(), declaredServiceTypes(scope));

        if (identity.typeName() == null) {
            draft.veto("serviceIdentity", "§3", scope.libraryName(),
                    "the document names no type for this service type entry");
            return;
        }
        if (!identity.declaredByPackage()) {
            draft.veto("serviceIdentity", "§3", identity.typeName(),
                    "not declared by the resolved package version");
            return;
        }

        draft.setKind(KIND_FIXED);
        // For a cross-module type this is the bare type name; a downstream enricher's lookup against
        // this module's symbols is then a deliberate no-op unless the module declares the name itself.
        draft.setName(identity.typeName());
        draft.setServiceTypeModule(identity.serviceTypeModule());
        draft.setAlternatives(identity.alternatives());
        // Spec §3 `deprecated`, in the same prose form as §5.3's. Set here rather than in a component of
        // its own: it is a property of the service type's identity, and it must not survive the two vetoes
        // above -- a deprecation note on an entry that never renders is a note about nothing.
        draft.setDeprecated(scope.serviceType().deprecated());
    }

    /**
     * How many service types are genuine alternatives to this one — the count spec §3's optionality rule
     * is read against.
     *
     * <p>Not the size of {@code serviceTypes[]}: a service type the paired listener cannot host is not an
     * alternative to the ones it can, it is a different construct reached another way. The distinction is
     * spec §2's {@code services}, so the count comes from {@link ListenerPairingResolver}, which owns it.
     */
    private static int declaredServiceTypes(TriggerScope scope) {
        if (scope.document() == null || scope.document().serviceTypes() == null) {
            // A scope built without a document states nothing about alternatives; a single entry is the
            // safe reading, and it emits no note.
            return 1;
        }
        return ListenerPairingResolver.hostedServiceTypeCount(
                scope.listener(), scope.document().serviceTypes());
    }

    /**
     * Spec §3's {@code multiple*Allowed} pair — and the decision of which half of it is worth saying.
     *
     * <p><b>Only a prohibition is emitted.</b> Both keys are set on all 26 service types in the corpus, so
     * writing both unconditionally would land a note on essentially every trigger service the Copilot
     * renders. {@code true} grants a permission a generator would not exercise unprompted — the default
     * shape a model writes, one service on one listener, is legal either way — whereas {@code false}
     * forbids something a model can plausibly reach for: asked to consume two Kafka topics, the obvious
     * shape is two services on one listener, which {@code kafka} makes illegal. Across the whole corpus
     * this emits <b>three</b> lines instead of twenty-four.
     *
     * <p>The two keys are written <b>separately</b> rather than merged into one note: they answer different
     * questions, and {@code kafka} is the only service type where both fire, so a combined line would be
     * wrong for {@code trigger.google.calendar}.
     */
    void cardinality(TriggerScope scope, ServiceDraft draft) {
        CardinalityResolver.Cardinality cardinality =
                CardinalityResolver.resolve(scope.serviceType(), scope.listener());
        if (!cardinality.multipleListeners()) {
            draft.setSingleListenerOnly();
        }
        if (!cardinality.multipleServices()) {
            // The stronger of the two listener-side prohibitions. Emitted instead of, not alongside,
            // the same-type note: "at most one service" already implies "at most one of this type", and
            // stating both would read as two separate restrictions.
            draft.setSingleServiceOnly();
        } else if (!cardinality.multipleServicesOfSameType()) {
            draft.setSingleServicePerListenerOnly();
        }
    }

    /**
     * Spec §2 {@code listeners[].requiredImports} — the side-effect-only imports the generated code needs.
     *
     * <p>Carried on the <b>service</b> rather than hoisted to the library: the spec declares these on the
     * listener, so only code that actually uses that listener needs them.
     */
    void requiredImports(TriggerScope scope, ServiceDraft draft) {
        List<RequiredImportResolver.ImportDirective> directives =
                RequiredImportResolver.resolve(scope.listener());
        if (directives.isEmpty()) {
            return;
        }
        JsonArray imports = new JsonArray();
        for (RequiredImportResolver.ImportDirective directive : directives) {
            JsonObject entry = new JsonObject();
            entry.addProperty("module", directive.module());
            entry.addProperty("alias", directive.alias());
            imports.add(entry);
        }
        draft.setRequiredImports(imports);
    }

    /**
     * Spec §2.1 {@code listeners[].platformDependencies} — native artifacts the build cannot fetch. Carried
     * on the service for the same reason {@link #requiredImports} is.
     */
    void platformDependencies(TriggerScope scope, ServiceDraft draft) {
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

    /**
     * Spec §3 {@code serviceTypes[].identifier} — the identifier/base-path slot the generated service must
     * or may fill.
     *
     * <p>The wire shape mirrors the document's, {@code {presence, form[]}}, rather than a pre-rendered
     * string: turning {@code basePath} into {@code /basePath} is a syntax decision belonging to the
     * renderer, which already owns every other one.
     */
    void identifier(TriggerScope scope, ServiceDraft draft) {
        if (scope.serviceType() == null) {
            return;
        }
        IdentifierResolver.resolve(scope.serviceType().identifier()).ifPresent(slot -> {
            JsonObject json = new JsonObject();
            json.addProperty("presence", slot.required()
                    ? PresenceForm.PRESENCE_REQUIRED : PresenceForm.PRESENCE_OPTIONAL);
            JsonArray forms = new JsonArray();
            slot.forms().forEach(forms::add);
            json.add("form", forms);
            draft.setIdentifier(json);
        });
    }

    /**
     * Spec §2 {@code listeners[].type} — the listener a service attaches to, with its init parameters, and
     * §2's {@code services}, which says whether this service type may be attached to one at all.
     *
     * <p>Spec §2 is explicit that "No listener init fields are ever modeled" in the document, so every
     * parameter comes from the semantic model: names and types from the {@code init} signature,
     * descriptions from its doc comment, and declared defaults recovered from the syntax tree.
     *
     * <p>The built object is <b>cached and shared</b> by identity across every service entry of a library.
     * That sharing is load-bearing rather than incidental: a downstream enricher rewrites
     * {@code listener.name} in place for packages shipping a non-canonical listener class, and handing each
     * service its own copy would change how many times that rewrite is applied.
     *
     * <p>The cache is keyed on the <b>document's</b> listener entry, falling back to the class only when
     * there is none. Keying on the class alone was safe while every field came from the semantic model, but
     * §2's {@code deprecated} is authored per listener entry — and two entries may name one class, in which
     * case a class-keyed cache would hand the second entry the first's deprecation note.
     */
    void listener(TriggerScope scope, ServiceDraft draft) {
        Object key = scope.listener() != null ? scope.listener() : scope.listenerClass();
        draft.setListener(builtListeners.computeIfAbsent(key, unused -> buildListener(scope)));
        // The listener is still emitted either way — a consumer needs its types even when the service is
        // written some other way, and the type closure reaches them through it.
        if (scope.document() != null
                && !ListenerPairingResolver.isHostedByAnyListener(
                        scope.document().listeners(), scope.serviceType())) {
            draft.setNotListenerAttachable();
        }
    }

    private static JsonObject buildListener(TriggerScope scope) {
        ClassSymbol listenerClass = scope.listenerClass();
        String packageName = scope.packageName();
        String className = listenerClass.getName().orElse(DEFAULT_LISTENER_NAME);

        JsonObject listenerObj = new JsonObject();
        listenerObj.addProperty("name", TypeRefResolver.moduleAlias(packageName) + ":" + className);
        // Spec §2 `deprecated`: prose, not a flag. The document says *why* the listener is superseded, and
        // that sentence is the only thing that tells a reader what to use instead.
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
