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

package io.ballerina.modelgenerator.commons.trigger.models;

import java.util.List;

/**
 * Spec §5 — a constraint on one part of a resource handler's signature.
 *
 * <p>A resource handler is identified by its accessor and its path, matching the language form
 * {@code resource function <accessor> <path>()}. Both are required for {@code kind: "resource"} and
 * neither applies to {@code kind: "remote"}.
 *
 * <p><b>Library-neutral, and that is the point.</b> This one type replaced four: HTTP's {@code method} and
 * {@code path}, and GraphQL's {@code accessor} and {@code fieldName}. HTTP calls its accessor a method and
 * GraphQL calls its path a field name, but both are the same two positions in the same language construct,
 * so the schema names them once. GraphQL's operation kind went with them, because it follows from what is
 * already here: a query is {@code resource} with accessor {@code get}, a subscription is {@code resource}
 * with accessor {@code subscribe}, and a mutation is {@code remote}.
 *
 * <p>No syntactic {@code form} is recorded any more either — the language already fixes what a resource
 * path may look like, so the old {@code identifierSegments}/{@code pathParamSegments} vocabulary was
 * restating the grammar.
 *
 * @param presence {@code "required"} or {@code "optional"}
 * @param values   the legal literal values, or a single {@link #ANY} meaning any value the language
 *                 accepts; {@code null} for a slot that constrains presence only, such as {@code path}
 * @since 1.10.0
 */
public record ValueSpec(String presence, List<String> values) {

    public static final String PRESENCE_REQUIRED = "required";
    public static final String PRESENCE_OPTIONAL = "optional";

    /** Spec §5: a single {@code "*"} in {@code values} means any value the language accepts. */
    public static final String ANY = "*";

    /** Whether this slot must be written. */
    public boolean isRequired() {
        return PRESENCE_REQUIRED.equals(presence);
    }

    /** Whether the document leaves the value open rather than enumerating a vocabulary. */
    public boolean isOpen() {
        return values != null && values.size() == 1 && ANY.equals(values.get(0));
    }
}
