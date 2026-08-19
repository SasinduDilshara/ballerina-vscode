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
 * A reference to a Ballerina type from within a {@link TriggerMetadataModel} document — a listener
 * class, a service type, an annotation type, or a parameter/return type.
 *
 * <h2>A tree, not a type expression in a string</h2>
 *
 * <p>The spec makes a type reference a <b>tree</b>. A node is either a plain {@link #name}, or a constructed
 * type given by {@link #shape} plus the parts that shape is built from:
 *
 * <pre>
 *   { "name": "Caller" }
 *   { "shape": "array",  "elementType": { "name": "byte", "builtin": true } }
 *   { "shape": "stream", "elementType": { "name": "anydata", "builtin": true },
 *                        "completionType": [{ "name": "Error" }, { "name": "()", "builtin": true }] }
 *   { "shape": "readonly", "elementType": { "shape": "array",
 *                                           "elementType": { "name": "byte", "builtin": true } } }
 * </pre>
 * which are {@code Caller}, {@code byte[]}, {@code stream<anydata, Error?>} and {@code readonly & byte[]}.
 *
 * <p><b>Why this replaced the string form, in one example.</b> The old shape wrote
 * {@code "stream<anydata, Error?>"} as a single name, which gave a consumer nothing to attach a module to:
 * the inner {@code Error} was invisible to any prefixing rule that worked on the leading identifier, so
 * qualification either produced {@code grpc:stream<anydata, Error?>} or left {@code Error} bare — and
 * neither resolves. The corpus worked around it by hard-coding {@code grpc:Error?} into the document, which
 * put a module prefix in a field that is supposed to be prefix-free. With a tree, every leaf is its own
 * {@code TypeRef}, carries its own {@link #packageInfo}, and is qualified independently, so the workaround
 * is no longer needed.
 *
 * <p><b>{@code shape} is closed, unlike the spec rule registry.</b> The spec is explicit about the
 * asymmetry: an unrecognised rule can be skipped and the rest of the manifest still used, whereas an
 * unrecognised type shape cannot — the type could not be written at all — so it must fail loudly rather
 * than silently.
 *
 * <p><b>Flat record, discriminated at read time.</b> The variants are modelled as one record with
 * mutually exclusive slots rather than a sealed hierarchy, because Gson discriminates on a field value only
 * with a custom deserializer, and the shape check belongs to the validator anyway — which reports a node
 * carrying the wrong fields for its kind far more usefully than a parse failure would. {@link #isNamed()}
 * and {@link #isComposite()} are the intended readers.
 *
 * @param name           the type's simple name for a named node, e.g. {@code "Caller"}. The spec
 *                       constrains it to a bare identifier, {@code "()"} or <code>"record {}"</code> — it never
 *                       embeds {@code []}, {@code <>} or a trailing {@code ?}, all of which are shapes or
 *                       unions now. {@code null} for a composite node
 * @param packageInfo    the originating module's coordinates; {@code null} for a same-module reference.
 *                       Only ever set alongside {@code name}
 * @param builtin        the spec §1.3 — {@code TRUE} only when {@code name} is one of Ballerina's own
 *                       language types ({@code int}, {@code anydata}, {@code error}, {@code ()},
 *                       <code>record {}</code>, …). Boxed and never {@code false}: the spec leaves the key
 *                       out on a module type, and it is what decides whether a leaf takes a module prefix.
 *                       Only ever set alongside {@code name}
 * @param subtypeFamily  the spec §1.4 — {@code TRUE} when this reference stands for the named type
 *                       <i>and every introspectable subtype of it</i> rather than the exact type alone
 *                       ({@code http:StatusCodeResponse} covering {@code http:Ok}, {@code http:Created},
 *                       and a user's own narrowing). Meaningful wherever a reference names a relationship a
 *                       declared type must satisfy rather than the declared type itself: a data binding's
 *                       {@code constraint}, its {@code excludes}, and a shape's {@code envelope}. Boxed and
 *                       never {@code false}, for the reason {@code builtin} is
 * @param shape          {@link #SHAPE_ARRAY}, {@link #SHAPE_STREAM} or {@link #SHAPE_READONLY} for a
 *                       composite node; {@code null} for a named one
 * @param elementType    what the shape holds: the element of an array, the value of a stream, the type a
 *                       {@code readonly} intersects with. Modelled as a list because the spec lets any type
 *                       position be a union
 * @param completionType what a stream terminates with; {@code null} for an array and for a
 *                       {@code readonly}, neither of which terminates with anything, and optional even for
 *                       a stream
 * @since 1.10.0
 */
public record TypeRef(String name,
                      PackageInfo packageInfo,
                      Boolean builtin,
                      Boolean subtypeFamily,
                      String shape,
                      List<TypeRef> elementType,
                      List<TypeRef> completionType) {

    /** The spec {@code shape: "array"} — {@code T[]}. */
    public static final String SHAPE_ARRAY = "array";
    /** The spec {@code shape: "stream"} — {@code stream<T>} or {@code stream<T, C>}. */
    public static final String SHAPE_STREAM = "stream";
    /** The spec {@code shape: "readonly"} — {@code readonly & T}. */
    public static final String SHAPE_READONLY = "readonly";

    /** A named node, the common case. */
    public TypeRef(String name, PackageInfo packageInfo) {
        this(name, packageInfo, null, null, null, null, null);
    }

    /** A composite node, whose parts carry their own {@code builtin}/{@code subtypeFamily} flags. */
    public TypeRef(String name, PackageInfo packageInfo, String shape, List<TypeRef> elementType,
                   List<TypeRef> completionType) {
        this(name, packageInfo, null, null, shape, elementType, completionType);
    }

    /** Whether this node is a plain named type. */
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
