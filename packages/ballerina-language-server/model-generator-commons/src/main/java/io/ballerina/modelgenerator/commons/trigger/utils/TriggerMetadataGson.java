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

package io.ballerina.modelgenerator.commons.trigger.utils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import io.ballerina.modelgenerator.commons.trigger.models.TriggerMetadataModel;
import io.ballerina.modelgenerator.commons.trigger.models.TypeRef;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@link Gson} instance for deserializing a {@code trigger-metadata.json} document into a
 * {@link TriggerMetadataModel}.
 *
 * <p>It exists for one construct: spec §1's {@link TypeRef}, which is both <b>polymorphic</b> and
 * <b>recursive</b>, and which appears in slots that may hold either a single type or a union.
 *
 * <ul>
 *   <li><b>Polymorphic.</b> A node is either {@code {"name": …}} or {@code {"shape": …, "elementType": …}}.
 *       Gson discriminates on a field value only with a custom deserializer.</li>
 *   <li><b>Recursive.</b> {@code elementType} and {@code completionType} are themselves {@code TypeRef}
 *       slots, so the same single-or-union normalization has to apply at every depth —
 *       {@code {"shape":"array","elementType":{"name":"byte"}}} nests a bare object where a list is
 *       modelled.</li>
 *   <li><b>Union-or-single.</b> Every type position may be written as one object or an array of them, and
 *       is always modelled as {@code List<TypeRef>} so callers never branch on the raw shape.</li>
 * </ul>
 *
 * <p><b>The tree is walked by hand rather than by re-entering Gson.</b> Reflective record deserialization
 * reuses a per-adapter constructor-argument buffer, so re-entering the same adapter graph while an ancestor
 * record is still mid-populate can corrupt it and misassign fields — previously observed as a
 * {@code ClassCastException} between {@code TypeRef} and {@code TypeRef[]}. The old code dodged that with a
 * second, adapter-less {@code Gson} for leaves, which worked only while leaves were flat. They are not flat
 * any more: an adapter-less parse of {@code {"shape":"array","elementType":{…}}} would try to read a bare
 * object into {@code List<TypeRef>} and fail. Constructing the node directly removes both problems, and
 * makes the one-or-many rule literally the same code at every level.
 *
 * @since 1.10.0
 */
public final class TriggerMetadataGson {

    private static final Type TYPE_REF_LIST = new TypeToken<List<TypeRef>>() { }.getType();

    private static final Gson INSTANCE = new GsonBuilder()
            .registerTypeAdapter(TYPE_REF_LIST, new TypeRefListDeserializer())
            .registerTypeAdapter(TypeRef.class, new TypeRefDeserializer())
            .create();

    private TriggerMetadataGson() {
    }

    /** The shared, preconfigured {@link Gson} instance for {@code trigger-metadata.json} documents. */
    public static Gson instance() {
        return INSTANCE;
    }

    /**
     * Reads one {@link TypeRef} node, whichever of spec §1's variants it is.
     *
     * <p>An unknown {@code shape} is carried through rather than rejected here: the parse stays total, and
     * {@code TypeRefCheck} reports it against the exact document path — which is a far more useful
     * diagnostic than a parse failure, and is where spec §1.1's "fail loudly" belongs.
     */
    static TypeRef readNode(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        JsonObject object = element.getAsJsonObject();
        if (object.has("shape")) {
            return new TypeRef(null, null,
                    object.get("shape").isJsonPrimitive() ? object.get("shape").getAsString() : null,
                    readList(object.get("elementType")),
                    readList(object.get("completionType")));
        }
        String name = object.has("name") && object.get("name").isJsonPrimitive()
                ? object.get("name").getAsString() : null;
        TypeRef.PackageInfo packageInfo = null;
        if (object.has("packageInfo") && object.get("packageInfo").isJsonObject()) {
            JsonObject info = object.getAsJsonObject("packageInfo");
            packageInfo = new TypeRef.PackageInfo(
                    string(info, "org"), string(info, "packageName"),
                    string(info, "moduleName"), string(info, "version"));
        }
        return new TypeRef(name, packageInfo);
    }

    /** Spec §1's one-or-many rule: a bare object is a singleton, an array is the union, in order. */
    static List<TypeRef> readList(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        List<TypeRef> refs = new ArrayList<>();
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (JsonElement member : array) {
                TypeRef ref = readNode(member);
                if (ref != null) {
                    refs.add(ref);
                }
            }
            return refs;
        }
        TypeRef ref = readNode(element);
        if (ref != null) {
            refs.add(ref);
        }
        return refs;
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : null;
    }

    /** Normalizes a {@code TypeRef}-or-union slot onto {@code List<TypeRef>}. */
    private static final class TypeRefListDeserializer implements JsonDeserializer<List<TypeRef>> {

        @Override
        public List<TypeRef> deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
                throws JsonParseException {
            return json == null || json.isJsonNull() ? null : readList(json);
        }
    }

    /** Reads a slot modelled as a single {@code TypeRef}, such as a listener or annotation type. */
    private static final class TypeRefDeserializer implements JsonDeserializer<TypeRef> {

        @Override
        public TypeRef deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
                throws JsonParseException {
            return json == null || json.isJsonNull() ? null : readNode(json);
        }
    }
}
