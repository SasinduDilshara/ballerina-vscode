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
 * <p>Spec §1 makes a type reference a <b>tree</b>. A node is either a plain {@link #name}, or a constructed
 * type given by {@link #shape} plus the parts that shape is built from:
 *
 * <pre>
 *   { "name": "Caller" }
 *   { "shape": "array",  "elementType": { "name": "byte" } }
 *   { "shape": "stream", "elementType": { "name": "anydata" },
 *                        "completionType": [{ "name": "Error" }, { "name": "()" }] }
 * </pre>
 * which are {@code Caller}, {@code byte[]} and {@code stream<anydata, Error?>}.
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
 * <p><b>{@code shape} is closed, unlike the §6.2 rule registry.</b> Spec §1.1 is explicit about the
 * asymmetry: an unrecognised rule can be skipped and the rest of the manifest still used, whereas an
 * unrecognised type shape cannot — the type could not be written at all — so it must fail loudly rather
 * than silently.
 *
 * <p><b>Flat record, discriminated at read time.</b> The three variants are modelled as one record with
 * mutually exclusive slots rather than a sealed hierarchy, because Gson discriminates on a field value only
 * with a custom deserializer, and the shape check belongs to the validator anyway — which reports a node
 * carrying the wrong fields for its kind far more usefully than a parse failure would. {@link #isNamed()}
 * and {@link #isComposite()} are the intended readers.
 *
 * @param name           the type's simple name for a named node, e.g. {@code "Caller"}. Spec §1
 *                       constrains it to a bare identifier, {@code "()"} or <code>"record {}"</code> — it never
 *                       embeds {@code []}, {@code <>} or a trailing {@code ?}, all of which are shapes or
 *                       unions now. {@code null} for a composite node
 * @param packageInfo    the originating module's coordinates; {@code null} for a same-module reference.
 *                       Only ever set alongside {@code name}
 * @param shape          {@link #SHAPE_ARRAY} or {@link #SHAPE_STREAM} for a composite node; {@code null}
 *                       for a named one
 * @param elementType    what the shape holds: the element of an array, the value of a stream. Modelled as
 *                       a list because spec §1 lets any type position be a union
 * @param completionType what a stream terminates with; {@code null} for an array, which terminates with
 *                       nothing, and optional even for a stream
 * @since 1.10.0
 */
public record TypeRef(String name,
                      PackageInfo packageInfo,
                      String shape,
                      List<TypeRef> elementType,
                      List<TypeRef> completionType) {

    /** Spec §1.1 {@code shape: "array"} — {@code T[]}. */
    public static final String SHAPE_ARRAY = "array";
    /** Spec §1.1 {@code shape: "stream"} — {@code stream<T>} or {@code stream<T, C>}. */
    public static final String SHAPE_STREAM = "stream";

    /** A named node, the common case. */
    public TypeRef(String name, PackageInfo packageInfo) {
        this(name, packageInfo, null, null, null);
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
