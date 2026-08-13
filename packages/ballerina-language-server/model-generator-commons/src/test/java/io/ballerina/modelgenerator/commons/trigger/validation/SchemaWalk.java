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

package io.ballerina.modelgenerator.commons.trigger.validation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * A deliberately small JSON-schema walker, sufficient for the clauses this repo's trigger schema actually
 * uses.
 *
 * <p><b>Why not a real library.</b> None is on this build's classpath, and adding one would break every
 * {@code --offline} build that has not cached it — the same constraint that produced the hand-maintained
 * mirror this replaces. The mirror was the worse trade: it duplicated the schema's top-level clauses into
 * a {@code Set} and enforced nothing below the top level, so a schema edit and a stale copy of that edit
 * were indistinguishable from the test's point of view.
 *
 * <p><b>What it enforces</b>, recursively, resolving {@code $ref} within the document:
 * <ul>
 *   <li>{@code required} — a listed property is present;</li>
 *   <li>{@code additionalProperties: false} — no property outside {@code properties};</li>
 *   <li>{@code enum} — a scalar is one of the listed values.</li>
 * </ul>
 *
 * <p><b>What it deliberately does not enforce</b>: {@code type}, {@code oneOf}/{@code anyOf}, numeric
 * bounds, {@code pattern}. Those clauses either do not appear in this schema or would need a real
 * implementation to get right, and a walker that silently half-implements a clause is worse than one that
 * visibly omits it. Every finding it reports is a genuine violation; it makes no claim to report all of
 * them.
 *
 * @since 1.10.0
 */
final class SchemaWalk {

    private SchemaWalk() {
        // Prevent instantiation
    }

    /**
     * Validates a document against a schema.
     *
     * @param schema   the parsed schema, which also supplies {@code $defs} for {@code $ref} resolution
     * @param document the parsed document
     * @return every violation found, as {@code path: reason}; empty when the document conforms
     */
    static List<String> validate(JsonObject schema, JsonObject document) {
        List<String> violations = new ArrayList<>();
        walk(schema, schema, document, "", violations);
        return violations;
    }

    private static void walk(JsonObject root, JsonObject schema, JsonElement value, String path,
                             List<String> violations) {
        JsonObject resolved = resolve(root, schema);
        if (resolved == null) {
            return;
        }
        if (resolved.has("enum") && value.isJsonPrimitive()) {
            checkEnum(resolved.getAsJsonArray("enum"), value, path, violations);
        }
        if (value.isJsonArray()) {
            walkArray(root, resolved, value.getAsJsonArray(), path, violations);
            return;
        }
        if (!value.isJsonObject()) {
            return;
        }
        walkObject(root, resolved, value.getAsJsonObject(), path, violations);
    }

    private static void walkArray(JsonObject root, JsonObject schema, JsonArray array, String path,
                                  List<String> violations) {
        if (!schema.has("items")) {
            return;
        }
        JsonObject items = schema.getAsJsonObject("items");
        for (int i = 0; i < array.size(); i++) {
            walk(root, items, array.get(i), path + "[" + i + "]", violations);
        }
    }

    private static void walkObject(JsonObject root, JsonObject schema, JsonObject object, String path,
                                   List<String> violations) {
        JsonObject properties = schema.has("properties")
                ? schema.getAsJsonObject("properties") : new JsonObject();

        if (schema.has("required")) {
            for (JsonElement required : schema.getAsJsonArray("required")) {
                String name = required.getAsString();
                if (!object.has(name)) {
                    violations.add(join(path, name) + ": required by the schema but absent");
                }
            }
        }
        // Absent `additionalProperties` defaults to permissive in JSON Schema; only an explicit `false`
        // closes the object. Reading the default as strict would reject documents the schema allows.
        boolean closed = schema.has("additionalProperties")
                && schema.get("additionalProperties").isJsonPrimitive()
                && !schema.get("additionalProperties").getAsBoolean();

        for (String name : object.keySet()) {
            if (!properties.has(name)) {
                if (closed) {
                    violations.add(join(path, name)
                            + ": not declared by the schema (additionalProperties: false)");
                }
                continue;
            }
            walk(root, properties.getAsJsonObject(name), object.get(name), join(path, name), violations);
        }
    }

    private static void checkEnum(JsonArray allowed, JsonElement value, String path,
                                  List<String> violations) {
        for (JsonElement candidate : allowed) {
            if (candidate.equals(value)) {
                return;
            }
        }
        violations.add(path + ": '" + value.getAsString() + "' is outside the schema's enum " + allowed);
    }

    /** Follows a {@code $ref} into {@code $defs}; a schema without one is returned unchanged. */
    private static JsonObject resolve(JsonObject root, JsonObject schema) {
        if (!schema.has("$ref")) {
            return schema;
        }
        String ref = schema.get("$ref").getAsString();
        String prefix = "#/$defs/";
        if (!ref.startsWith(prefix)) {
            // An external or unsupported reference. Skipped rather than guessed at — see the class note
            // on reporting only genuine violations.
            return null;
        }
        JsonObject defs = root.getAsJsonObject("$defs");
        String name = ref.substring(prefix.length());
        return defs != null && defs.has(name) ? defs.getAsJsonObject(name) : null;
    }

    private static String join(String path, String name) {
        return path.isEmpty() ? name : path + "." + name;
    }
}
