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
import io.ballerina.modelgenerator.commons.trigger.models.TriggerMetadataModel;
import io.ballerina.modelgenerator.commons.trigger.utils.TypeRefResolver;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * The parameter tier of spec §7, plus spec §9's data binding. Each method runs once per parameter slot of a
 * handler.
 *
 * <p>The same provenance split as the handler tier applies: a <b>declared</b> parameter of a concrete
 * service type supplies its own name, type and optionality; a <b>metadata</b> slot supplies its type and
 * presence, and its name only sometimes. §8 parameter annotations are {@link AnnotationAspects#param}.
 *
 * <p>Order carries no meaning — {@link ParamDraft} holds each slot as a field and emits the wire contract's
 * key order itself.
 *
 * @since 1.7.0
 */
final class ParamAspects {

    private ParamAspects() {
        // Prevent instantiation
    }

    /**
     * Spec §7 {@code params[]} — one slot's name, description and type.
     *
     * <p>§7 calls {@code name} an "optional domain-meaningful name", so where the document omits it a
     * deterministic one is generated. This is the only place in the pipeline where a name is synthesized
     * rather than read. A repeatable slot is exempt: §7 says its occurrences are "each independently named"
     * by the author, so there is no single name for it and none is synthesized — though an authored name is
     * still a real fact and is kept.
     *
     * <p>Spec §5.1's rule applies to parameters too: a documented slot of a marker-type handler has no
     * symbol behind it, so the document's {@code doc} is the only description of what it carries.
     */
    static void type(ParamScope scope, ParamDraft draft) {
        TriggerScope service = scope.handler().service();
        String packageName = service.packageName();

        if (scope.declared() != null) {
            TriggerSemanticFacts.DeclaredParam declared = scope.declared();
            draft.setName(declared.name());
            draft.setDescription(declared.description());
            draft.setType(TypeResolver.resolveTypeWithLinks(
                    declared.typeSignature() != null ? declared.typeSignature() : "", packageName));
            // Optionality is `presence`, for both sources.
            return;
        }

        TriggerMetadataModel.ServiceType.Param param = scope.param();
        // Reading ParamRepeatResolver's predicate rather than the raw key leaves §7's `addMode` with
        // exactly one owner.
        String name = ParamRepeatResolver.isRepeatable(param)
                ? param.name()
                : ParamTypeResolver.resolveName(param, scope.position(),
                        TypeRefResolver.moduleAlias(packageName), scope.siblingNames());
        draft.setDescription(param.doc());
        // Spec §7 `deprecated`, the parameter-scope twin of §5.3's. No corpus slot states one yet; the
        // wiring is here because the alternative is that the first document to state one loses it silently.
        draft.setDeprecated(param.deprecated());
        if (name != null) {
            scope.siblingNames().add(name);
        }

        ParamTypeResolver.ParamType resolved = ParamTypeResolver.resolveType(param, packageName,
                service.declaresType());

        draft.setName(name);
        draft.setType(TypeResolver.resolveTypeWithLinks(resolved.signature(), packageName));

        // Spec §7's other legal types, as link-carrying pairs so the type closure reaches their
        // definitions. Never joined with `|` — see ParamTypeResolver.ParamType.
        JsonArray alternatives = new JsonArray();
        for (String alternative : resolved.alternatives()) {
            alternatives.add(TypeResolver.resolveTypeWithLinks(alternative, packageName));
        }
        draft.setAlternatives(alternatives);

        for (String undeclared : resolved.dropped()) {
            draft.drop("paramType", "§7", undeclared,
                    "an alternative type the resolved package version does not declare");
        }
    }

    /**
     * Spec §7 {@code params[].presence} — whether the slot may be omitted from the signature. Reads
     * whichever source the handler came from: a metadata slot states {@code presence}, a declared parameter
     * carries the compiler's answer.
     */
    static void presence(ParamScope scope, ParamDraft draft) {
        draft.setOptional(scope.declared() != null
                ? ParamPresenceResolver.isOptional(scope.declared())
                : ParamPresenceResolver.isOptional(scope.param()));
    }

    /**
     * Spec §7 {@code params[].addMode} — the flag that takes a slot out of the fixed signature.
     *
     * <p>Metadata-described slots only: a declared parameter either exists or does not, and there is no
     * notion of one that repeats.
     */
    static void repeat(ParamScope scope, ParamDraft draft) {
        if (ParamRepeatResolver.isRepeatable(scope.param())) {
            draft.setRepeatable(true);
        }
    }

    /**
     * Spec §9 {@code params[].dataBinding} — how a parameter's raw value may be projected into a
     * user-defined type.
     *
     * <p>Every type name is written as a {@code {name, links}} pair rather than bare text, because the type
     * closure that decides which definitions reach the prompt walks links. A binding note naming
     * {@code AnydataConsumerRecord} with no way to reach its declaration would tell the model to include a
     * record the file never defines.
     *
     * <p><b>The wire shape mirrors the document's</b> — variants, each with a bound, its exclusions and its
     * shapes — rather than flattening to a single {@code modes} array. Flattening would have to pick one
     * bound per binding, and §9's whole point is that two variants can share shapes while differing in bound
     * (ftp's CSV rows: {@code string[]} or {@code record {}}), or share a bound while differing in shape
     * (kafka's bare-vs-included). Either collapse silently deletes half the surface.
     */
    static void dataBinding(ParamScope scope, ParamDraft draft) {
        TriggerMetadataModel.ServiceType.Param param = scope.param();
        if (param == null || param.dataBinding() == null) {
            return;
        }
        TriggerScope service = scope.handler().service();
        String packageName = service.packageName();
        Optional<DataBindingResolver.BindingSpec> spec = DataBindingResolver.resolve(
                param.dataBinding(), packageName, service.declaresType(), envelopeFields(service));

        if (spec.isEmpty()) {
            // A binding is written inline, so there is no id to have mis-resolved: the only way here is a
            // binding whose every variant was unusable. Reported against the parameter, which is also where
            // the document author has to edit.
            draft.drop("dataBinding", "§9", param.name() == null ? "<unnamed param>" : param.name(),
                    "its dataBinding declares no variant with both a bound and a readable shape");
            return;
        }
        draft.setBinding(toJson(spec.get(), packageName));
    }

    /**
     * The envelope-field lookup spec §9's derived {@code fixedFields} needs, or an empty one when no
     * compiled package is behind this scope — in which case {@code fixedFields} is simply not derived,
     * rather than guessed.
     */
    private static Function<String, List<String>> envelopeFields(TriggerScope scope) {
        TriggerSemanticFacts facts = scope.facts();
        return facts == null ? name -> List.of() : facts::recordFieldNames;
    }

    private static JsonObject toJson(DataBindingResolver.BindingSpec spec, String packageName) {
        JsonObject json = new JsonObject();
        JsonArray variants = new JsonArray();
        for (DataBindingResolver.Variant variant : spec.variants()) {
            variants.add(variantToJson(variant, packageName));
        }
        json.add("typedescs", variants);
        return json;
    }

    private static JsonObject variantToJson(DataBindingResolver.Variant variant, String packageName) {
        JsonObject json = new JsonObject();
        json.add("constraint", TypeResolver.resolveTypeWithLinks(variant.constraint(), packageName));
        addTypes(json, "excludes", variant.excludes(), packageName);
        JsonArray shapes = new JsonArray();
        for (ShapeResolver.ResolvedShape shape : variant.shapes()) {
            shapes.add(shapeToJson(shape, packageName));
        }
        json.add("shapes", shapes);
        return json;
    }

    private static JsonObject shapeToJson(ShapeResolver.ResolvedShape shape, String packageName) {
        JsonObject json = new JsonObject();
        json.addProperty("form", shape.form());
        if (shape.element() != null) {
            json.addProperty("element", shape.element());
        }
        if (shape.envelope() != null) {
            json.add("envelope", TypeResolver.resolveTypeWithLinks(shape.envelope(), packageName));
        }
        addStrings(json, "bindableFields", shape.bindableFields());
        addStrings(json, "fixedFields", shape.fixedFields());
        if (shape.completionType() != null) {
            json.add("completionType",
                    TypeResolver.resolveTypeWithLinks(shape.completionType(), packageName));
        }
        return json;
    }

    private static void addTypes(JsonObject json, String key, List<String> signatures, String packageName) {
        if (signatures == null || signatures.isEmpty()) {
            return;
        }
        JsonArray types = new JsonArray();
        for (String signature : signatures) {
            types.add(TypeResolver.resolveTypeWithLinks(signature, packageName));
        }
        json.add(key, types);
    }

    private static void addStrings(JsonObject json, String key, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        json.add(key, array);
    }
}
