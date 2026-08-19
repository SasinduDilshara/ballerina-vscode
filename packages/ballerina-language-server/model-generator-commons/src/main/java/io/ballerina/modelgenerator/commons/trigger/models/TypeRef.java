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
 * A Ballerina type reference.
 *
 * @param name           a plain type name; mutually exclusive with {@code shape}
 * @param packageInfo    cross-module origin; {@code null} for same-module
 * @param builtin        {@code true} only for one of Ballerina's own language types; never {@code false}
 * @param subtypeFamily  {@code true} when this reference stands for the named type and every
 *                       introspectable subtype of it, not the exact type alone; never {@code false}
 * @param shape          {@link #SHAPE_ARRAY}, {@link #SHAPE_STREAM} or {@link #SHAPE_READONLY};
 *                       {@code null} for a named type
 * @param elementType    the array element, stream value, or readonly-intersected type
 * @param completionType what a stream terminates with; stream-only, optional
 * @since 1.10.0
 */
public record TypeRef(String name, PackageInfo packageInfo, Boolean builtin, Boolean subtypeFamily, String shape,
                      List<TypeRef> elementType, List<TypeRef> completionType) {

    /** {@code T[]}. */
    public static final String SHAPE_ARRAY = "array";
    /** {@code stream<T>} or {@code stream<T, C>}. */
    public static final String SHAPE_STREAM = "stream";
    /** {@code readonly & T}. */
    public static final String SHAPE_READONLY = "readonly";

    public TypeRef(String name, PackageInfo packageInfo) {
        this(name, packageInfo, null, null, null, null, null);
    }

    /** A composite node, whose parts carry their own {@code builtin}/{@code subtypeFamily} flags. */
    public TypeRef(String name, PackageInfo packageInfo, String shape, List<TypeRef> elementType,
                   List<TypeRef> completionType) {
        this(name, packageInfo, null, null, shape, elementType, completionType);
    }

    public boolean isNamed() {
        return shape == null;
    }

    /** Whether this node is a constructed type. */
    public boolean isComposite() {
        return shape != null;
    }

    /**
     * Whether the spec marks this leaf as one of the language's own types, which is what decides that it
     * must never take a module prefix.
     *
     * <p>Read rather than inferred: the spec §1.2 says outright that "a consumer should read {@code
     * builtin} rather than pattern match on casing", because the casing convention that separates
     * {@code error} from a module's {@code Error} is an authoring habit and not a rule.
     */
    public boolean isBuiltin() {
        return Boolean.TRUE.equals(builtin);
    }

    /**
     * Whether this reference stands for a whole subtype family rather than one exact type — the spec §1.4.
     *
     * <p>Only a rendering decision follows from it: the type name written is the same either way, but a
     * note describing what a reader may declare has to say "any subtype of" rather than naming a single
     * record, since the family is open-ended over the user's own narrowings.
     */
    public boolean isSubtypeFamily() {
        return Boolean.TRUE.equals(subtypeFamily);
    }

    /**
     * The coordinates of the module a cross-module {@link TypeRef} originates from.
     *
     * @param org         the organization, e.g. {@code "ballerina"}
     * @param packageName the package name, e.g. {@code "http"}
     * @param moduleName  the module name, e.g. {@code "http"}
     * @param version     the package version, e.g. {@code "2.16.5"}
     */
    public record PackageInfo(String org, String packageName, String moduleName, String version) {
    }
}
