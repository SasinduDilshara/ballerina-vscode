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

import java.util.ArrayList;
import java.util.List;

/**
 * The accumulating output of one handler, written by the handler-level components.
 *
 * <p>Every slot is <b>held as a field and emitted once in {@link #toJson()}</b>, rather than written straight
 * into a {@link JsonObject} as each component runs. That is what lets the wire contract's key order be a
 * property of this class instead of a property of {@link AspectRegistry}'s ordering: {@code kind} is owned by
 * a different component from {@code name}, and neither should have to run in a particular position just to
 * keep the emitted JSON reading naturally.
 *
 * <p>A {@code description} is emitted only when one genuinely exists. For a marker service type neither
 * source has one — the metadata document does not model descriptions, and the library declares no method to
 * carry a doc comment — so the key is omitted rather than fabricated.
 *
 * @since 1.7.0
 */
final class HandlerDraft {

    private final JsonArray parameters = new JsonArray();
    private final List<Veto> vetoes = new ArrayList<>();
    // Non-fatal: a dropped annotation reference, or one dropped from a parameter. Reported, but the handler
    // still renders — the same policy a dropped handler follows towards its service.
    private final List<Veto> diagnostics = new ArrayList<>();

    private JsonArray annotationRefs;
    private JsonArray returnAnnotationRefs;
    private String name;
    private String kind;
    private String description;
    // A concrete type's declared `isolated` qualifier. Emitted only when true; see HandlerQualifierAspect.
    private Boolean isolated;
    // Spec §5 `presence`, tri-state on purpose: TRUE optional, FALSE required, null "the document is not
    // answering the question" (addMode: many). See HandlerPresenceResolver.
    private Boolean optional;
    private String accessor;
    private JsonArray accessorValues;
    private boolean accessorRequired;
    // Spec §5 `values: ["*"]` -- any accessor the language accepts. Kept apart from an enumerated list so a
    // consumer can word the two cases differently; see setAccessorConstraint.
    private boolean accessorOpen;
    private boolean pathRequired;
    // Spec §5's `path` is the same `valueSpec` as `accessor`, so it carries the same three facts. Only
    // `pathRequired` was kept, which silently discarded a document's path vocabulary.
    private String path;
    private JsonArray pathValues;
    private boolean pathOpen;
    private String deprecated;
    private JsonObject returnObj;

    /** Spec §5 {@code options[].name}, or a concrete type's declared method name. */
    void setName(String name) {
        this.name = name;
    }

    /** Spec §5 {@code options[].kind}: {@code "remote"} or {@code "resource"}. */
    void setKind(String kind) {
        this.kind = kind;
    }

    /**
     * The declared method carries {@code isolated}, so an implementation must repeat it or the signatures
     * do not match. Emitted only when true, per the omission rule.
     */
    void setIsolated() {
        this.isolated = true;
    }

    /** The method's doc-comment description; omitted when the source has none. */
    void setDescription(String description) {
        if (description != null && !description.isEmpty()) {
            this.description = description;
        }
    }

    /**
     * Spec §5 {@code options[].presence}, as optionality.
     *
     * <p>Unlike {@link ParamDraft#setOptional}, {@code false} <b>is</b> emitted: for a handler the
     * difference between "required" and "the document does not say" is real, and only an explicit
     * {@code false} can state the former. Absence is expressed by never calling this method.
     */
    void setOptional(boolean optional) {
        this.optional = optional;
    }

    /** The accessor a resource handler is written with, per {@link ResourceExtrasResolver}. */
    void setAccessor(String accessor) {
        if (accessor != null && !accessor.isEmpty()) {
            this.accessor = accessor;
        }
    }

    /**
     * Spec §5's {@code accessor} constraint: the legal accessors, whether one must be written, and whether
     * the document leaves the choice open ({@code values: ["*"]}).
     *
     * <p>{@code open} is carried separately rather than encoded as a magic {@code "*"} in the list, because
     * a consumer must word the two cases differently: an enumerated list becomes "must be one of `get`,
     * `post`", whereas an open slot becomes "any accessor the language accepts". Rendering the literal
     * {@code *} as a value would produce a note instructing the reader to write an accessor called `*`.
     */
    void setAccessorConstraint(List<String> values, boolean required, boolean open) {
        this.accessorRequired = required;
        this.accessorOpen = open;
        if (values != null && !values.isEmpty()) {
            this.accessorValues = toArray(values);
        }
    }

    /**
     * Spec §5's {@code path}: whether a resource path must be written, and — because {@code path} is the
     * same {@code valueSpec} as {@code accessor} — which paths are legal.
     *
     * <p>No syntactic <i>form</i> accompanies it. Spec §5 dropped the {@code identifierSegments} /
     * {@code pathParamSegments} vocabulary because "the language already fixes what a resource path may
     * look like" — the old list was restating the grammar, and a consumer that quoted it was telling the
     * reader something Ballerina already guarantees. A <b>value list</b> is a different claim: it names the
     * specific paths this connector accepts, which the language cannot know and only the document can state.
     * It was being dropped, in exact symmetry with the accessor vocabulary that went missing on the same hop.
     */
    void setPathConstraint(String path, List<String> values, boolean required, boolean open) {
        this.pathRequired = required;
        this.pathOpen = open;
        if (path != null && !path.isEmpty()) {
            this.path = path;
        }
        if (values != null && !values.isEmpty()) {
            this.pathValues = toArray(values);
        }
    }

    /**
     * Spec §5.3 {@code deprecated} — why this handler is superseded, as the document's own prose.
     *
     * <p>Carried as text rather than as a flag because the sentence <i>is</i> the useful part: {@code ftp}'s
     * {@code onFileChange} note names the five typed handlers that replace it, and a bare {@code true} would
     * tell a reader to stop using the handler while withholding what to use instead.
     */
    void setDeprecated(String deprecated) {
        if (deprecated != null && !deprecated.isBlank()) {
            this.deprecated = deprecated;
        }
    }

    /** Spec §5 {@code options[].returns}; omitted when the return carries no information. */
    void setReturn(JsonObject value) {
        this.returnObj = value;
    }

    /**
     * Spec §8 at {@code attachPoint: "function"} — the annotations this handler must or may carry.
     *
     * <p>{@code annotationRefs}, not {@code annotations}: the key names a <i>requirement</i>, matching the
     * name used at parameter scope where {@code annotations} is already taken by the semantic model's own
     * attachments. Consistency across the three new scopes beats saving a word at the one that is free.
     */
    void setAnnotationRefs(JsonArray refs) {
        if (refs != null && !refs.isEmpty()) {
            this.annotationRefs = refs;
        }
    }

    /**
     * Spec §8 at {@code attachPoint: "return"} — the annotations the handler's return must or may carry.
     *
     * <p>Held separately and merged into the {@code return} object by {@link #toJson()} rather than written
     * into it directly, so the component that resolves them does not have to run after the one that builds
     * the return. Dropped when the handler has no return object at all: there is no slot to attach to, and
     * a return that carries no type carries no annotation either.
     */
    void setReturnAnnotationRefs(JsonArray refs) {
        if (refs != null && !refs.isEmpty()) {
            this.returnAnnotationRefs = refs;
        }
    }

    /** Appends one built parameter, preserving declaration order, and adopts its diagnostics. */
    void addParam(ParamDraft param) {
        if (param != null) {
            diagnostics.addAll(param.diagnostics());
            parameters.add(param.toJson());
        }
    }

    /** Records that this handler must be dropped; the orchestrator performs the drop. */
    void veto(String aspectId, String specSection, String subject, String reason) {
        vetoes.add(new Veto(aspectId, specSection, subject, reason));
    }

    /**
     * Records that a contribution was dropped, without dropping the handler.
     *
     * <p>Distinct from {@link #veto} on purpose: an annotation reference that cannot be resolved makes the
     * <i>obligation</i> unusable, not the handler, and dropping a handler because one of its annotations
     * was mis-filed would lose far more than it protects.
     */
    void drop(String aspectId, String specSection, String subject, String reason) {
        diagnostics.add(new Veto(aspectId, specSection, subject, reason));
    }

    boolean isVetoed() {
        return !vetoes.isEmpty();
    }

    List<Veto> vetoes() {
        return vetoes;
    }

    /** Every non-fatal drop recorded while building this handler or its parameters. */
    List<Veto> diagnostics() {
        return diagnostics;
    }

    /**
     * The finished handler, in the wire contract's key order.
     *
     * <p>{@code parameters} is omitted when empty, which covers both a genuinely param-less handler and one
     * whose every slot was skipped as repeatable.
     */
    JsonObject toJson() {
        JsonObject json = new JsonObject();
        addIfPresent(json, "name", name);
        addIfPresent(json, "type", kind);
        if (isolated != null) {
            json.addProperty("isolated", isolated);
        }
        addIfPresent(json, "description", description);
        addIfPresent(json, "deprecated", deprecated);
        if (optional != null) {
            json.addProperty("optional", optional);
        }
        addIfPresent(json, "accessor", accessor);
        if (accessorValues != null) {
            json.add("accessorValues", accessorValues);
        }
        if (accessorRequired) {
            json.addProperty("accessorRequired", true);
        }
        if (accessorOpen) {
            json.addProperty("accessorOpen", true);
        }
        addIfPresent(json, "path", path);
        if (pathValues != null) {
            json.add("pathValues", pathValues);
        }
        if (pathRequired) {
            json.addProperty("pathRequired", true);
        }
        if (pathOpen) {
            json.addProperty("pathOpen", true);
        }
        if (annotationRefs != null) {
            json.add("annotationRefs", annotationRefs);
        }
        if (!parameters.isEmpty()) {
            json.add("parameters", parameters);
        }
        if (returnObj != null) {
            if (returnAnnotationRefs != null) {
                returnObj.add("annotationRefs", returnAnnotationRefs);
            }
            json.add("return", returnObj);
        }
        return json;
    }

    private static void addIfPresent(JsonObject json, String key, String value) {
        if (value != null) {
            json.addProperty(key, value);
        }
    }

    private static JsonArray toArray(List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }
}
