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
 * Deserialization target for a connector's own <b>{@code resources/trigger-metadata.json}</b> — the
 * authoring-rules overlay that sits above the syntax tree and semantic model: presence rules,
 * cross-construct relationships, identifier/base-path semantics, and handler shapes for service types
 * with no concrete backing type.
 *
 * <p>Deliberately separate from {@code TriggerArtifactModel} (the existing, display-only
 * {@code resources/trigger-artifact.json} sibling used for tree-node name/icon/label) and from the
 * much larger, fully UI-ready {@code trigger-ui-schema.json} (a {@link TriggerUISchemaModel}
 * Property/codedata tree). This document carries only what a coding agent or the language server
 * <b>cannot</b> recover by introspecting the library itself (the governing DRY principle: everything the
 * compiler API can already tell you — a listener's init signature, a concrete service type's declared
 * methods, an annotation record's field shapes — is referenced by name here, never restated).
 *
 * <h2>Spec v1.0 (m2)</h2>
 *
 * <p>This models spec <b>v1.0</b>, which restructured several constructs relative to the pre-release
 * {@code "v1"} documents. The differences a reader of the older shape will notice:
 * <ul>
 *   <li><b>§0</b> — ids and every reference to one are {@code $}-prefixed. The sigil is carried
 *       verbatim rather than stripped: it is part of the value, references and definitions gain it
 *       symmetrically, so equality comparisons are unaffected.</li>
 *   <li><b>§2/§3</b> — attachment cardinality is split three ways. Two facts describe the
 *       <i>listener instance</i> and moved onto {@link Listener}; only "may one service attach to
 *       several listeners" stays on {@link ServiceType}.</li>
 *   <li><b>§6</b> — {@code rules} became a reference to an open registry ({@link Rule}) instead of a
 *       closed {@code oneOf}/{@code atMostOne} enum, and gained a top-level array for constraints
 *       spanning more than one service type.</li>
 *   <li><b>§8</b> — the reverse {@code appliesTo} list is gone. Every annotation is now reached by a
 *       forward reference from the construct that carries it.</li>
 *   <li><b>§9</b> — the top-level {@code dataBindingRules} registry is gone; a binding is written
 *       inline on the parameter it describes ({@link DataBinding}).</li>
 * </ul>
 *
 * <p><b>Omission rule.</b> Omit {@link #annotations} / {@link #rules} entirely when the connector has
 * none — an empty array is never used in their place, consistent with the rest of this schema: a field
 * that would be empty, unused, or fully derivable from other fields is left out.
 *
 * <p><b>Boxed booleans are load-bearing.</b> Every optional boolean here is {@code Boolean}, not
 * {@code boolean}, so an absent key stays {@code null} rather than deserializing to {@code false}. A
 * consumer that states only the prohibition would otherwise turn an omission into a restriction the
 * document never made.
 *
 * @param version      the spec version this instance conforms to, e.g. {@code "v1.0"}. Spec §11 gives it
 *                     the form {@code v<major>.<minor>}; {@code SpecVersionGate} compares only the major,
 *                     because §11.3 requires an unknown <i>minor</i> to be read rather than refused
 * @param listeners    the connector's listener entry point(s); always at least one — a listener is
 *                     structurally required, so there is no presence marker on this array
 * @param serviceTypes the connector's service-type alternatives. Exactly one entry means it is the
 *                     (only, required) service type; more than one entry means each is individually
 *                     optional and the choice of which to implement is left to whatever supplied the
 *                     generation intent
 * @param annotations  registry of annotation types referenced elsewhere in this document, defined once;
 *                     {@code null}/absent when the connector declares none
 * @param rules        spec §6 constraints spanning more than one service type, where every
 *                     subject must name its {@code serviceType}. A rule scoped to a single service type
 *                     lives on that {@link ServiceType#rules()} instead; {@code null}/absent when none
 * @since 1.10.0
 */
public record TriggerMetadataModel(
        String version,
        List<Listener> listeners,
        List<ServiceType> serviceTypes,
        List<Annotation> annotations,
        List<Rule> rules) {

    /**
     * A listener entry point. No init fields are ever modeled here — a listener's init signature is
     * introspectable (the governing DRY principle), so a generator gets real init parameters from the
     * library itself, not from this file. There is no {@code presence} field — a listener is always
     * structurally required.
     *
     * <p>Spec §3.1 puts two of the three attachment-cardinality facts here rather than on the service
     * type, because both describe what <i>this listener instance</i> will accept.
     *
     * @param type                              the listener class
     * @param deprecated                        spec §5.3 — why this listener is deprecated, as prose;
     *                                          {@code null} when it is current
     * @param services                          the {@link ServiceType#id()} values this listener can host
     * @param multipleServicesAllowed           can one instance of this listener host more than one
     *                                          service at all? Boxed: absent states nothing
     * @param multipleServicesOfSameTypeAllowed can two of those services be of the same service type?
     *                                          Spec §2 omits it when {@code multipleServicesAllowed} is
     *                                          {@code false}, since one service at most already rules it
     *                                          out. Boxed: "absent means unconstrained"
     * @param requiredImports                   packages that must be imported for side effect even though
     *                                          nothing in the generated code references them by name
     *                                          (e.g. a generic engine needing a driver registered at
     *                                          runtime); {@code null}/absent when none are needed
     * @param platformDependencies              native dependencies the build cannot fetch;
     *                                          {@code null}/absent when the connector needs none
     */
    public record Listener(TypeRef type,
                           String deprecated,
                           List<String> services,
                           Boolean multipleServicesAllowed,
                           Boolean multipleServicesOfSameTypeAllowed,
                           List<RequiredImport> requiredImports,
                           List<PlatformDependency> platformDependencies) {
    }

    /**
     * A package that must be imported for its side effect (e.g. {@code import ballerinax/mssql.cdc.driver as _;})
     * even though nothing in the generated code references it by name.
     *
     * @param importType  the reason the import is required; {@link #IMPORT_TYPE_DRIVER} is the only
     *                    value seen in the corpus this schema was built against
     * @param packageInfo the coordinates of the package to import
     */
    public record RequiredImport(String importType, TypeRef.PackageInfo packageInfo) {

        public static final String IMPORT_TYPE_DRIVER = "driver";
    }

    /**
     * Spec §2.1 — a native artifact the build needs, mirroring {@code Ballerina.toml}'s
     * {@code [[platform.<javaVersion>.dependency]]} table.
     *
     * <p>Modeled because it is the one dependency class nothing else records: {@code requiredImports}
     * covers Ballerina packages, but a jar whose licence forbids publishing it — SAP JCo's
     * {@code sapjco3.jar} — appears in no repository a build can reach. There is deliberately no
     * {@code path}: that is the user's own download location. There is no {@code javaVersion} either;
     * the generator knows the Java version of the distribution it targets.
     *
     * @param groupId         Maven group id
     * @param artifactId      Maven artifact id
     * @param version         Maven version; may be a wildcard pattern such as {@code "3.1.*"}
     * @param scope           {@link #SCOPE_PROVIDED} keeps the jar compile-time only, for example when a
     *                        licence forbids bundling it. Absent means bundled
     * @param acquisition     how to obtain an artifact no public repository serves
     * @param nativeLibraries OS-specific libraries the JVM must load at run time. Modeled separately
     *                        because a missing native library is not a build failure: the package
     *                        compiles and the service then fails at run time, so nothing in the build
     *                        graph records the requirement
     */
    public record PlatformDependency(String groupId,
                                     String artifactId,
                                     String version,
                                     String scope,
                                     Acquisition acquisition,
                                     List<NativeLibrary> nativeLibraries) {

        public static final String SCOPE_PROVIDED = "provided";
    }

    /**
     * How to get an artifact no public repository serves.
     *
     * @param url  machine-actionable download location
     * @param note the human instructions; required, because a URL alone rarely identifies which artifact
     *             to take
     */
    public record Acquisition(String url, String note) {
    }

    /**
     * An OS-specific native library. Where it must be placed is determined by {@code os}, so spec §2.1
     * states that mapping once rather than repeating it per entry: {@code linux} →
     * {@code LD_LIBRARY_PATH}, {@code windows} → {@code PATH}, {@code macos} → {@code DYLD_LIBRARY_PATH}.
     *
     * @param os   {@link #OS_LINUX}, {@link #OS_WINDOWS} or {@link #OS_MACOS}
     * @param file the library file name
     */
    public record NativeLibrary(String os, String file) {

        public static final String OS_LINUX = "linux";
        public static final String OS_WINDOWS = "windows";
        public static final String OS_MACOS = "macos";
    }

    // Spec §5.3 made `deprecated` a plain string: "Presence of the field is the deprecation; the value is
    // the explanation." The record that used to model {reason, since, replacement} is gone -- a reason can
    // name a version or a successor in the sentence itself where either matters, and a generator puts the
    // text in the construct's `# # Deprecated` doc section beside `@deprecated`, which is where the
    // language expects the explanation to live.

    /**
     * One service-type alternative a connector exposes. See {@link TriggerMetadataModel#serviceTypes()}
     * for how array cardinality (rather than a {@code presence} field) determines whether an entry is
     * mandatory.
     *
     * @param id                       local identifier ({@code $}-prefixed), referenced from
     *                                 {@link Listener#services()} and from rule subjects
     * @param type                     the service object type
     * @param concrete                 {@code true} if the type declares its own methods directly (fully
     *                                 introspectable — {@code handlers} then carries
     *                                 {@code backedByConcreteType: true} and omits {@code addMode} and
     *                                 {@code options}); {@code false} for a marker/abstract type
     * @param multipleListenersAllowed can one service instance attach to more than one listener in a
     *                                 single declaration ({@code service X on l1, l2 {}})? Boxed so an
     *                                 absent key stays {@code null} rather than deserializing to
     *                                 {@code false} — a consumer that states only the prohibition would
     *                                 otherwise turn an omission into a restriction the document never
     *                                 made
     * @param deprecated               spec §5.3 — why this service type is deprecated, as prose;
     *                                 {@code null} when it is current
     * @param annotations              spec §8 — ids of annotations with {@code attachPoint: "service"}
     *                                 that this service type carries. The forward reference that replaced
     *                                 the old reverse {@code appliesTo} list
     * @param identifier               the identifier/base-path slot (the string/path after
     *                                 {@code service}); {@code null}/absent when the slot carries no
     *                                 meaning for this service type
     * @param handlers                 the handler catalog
     * @param rules                    spec §6 constraints scoped to this service type;
     *                                 {@code null}/absent when none apply
     * @since 1.10.0
     */
    public record ServiceType(
            String id,
            TypeRef type,
            boolean concrete,
            Boolean multipleListenersAllowed,
            String deprecated,
            List<String> annotations,
            PresenceForm identifier,
            Handlers handlers,
            List<Rule> rules) {

        /**
         * The handler catalog for one service type.
         *
         * <p><b>{@code addMode} moved off this block in spec §4</b>, onto each option. Whether a handler is
         * a fixed name or a repeatable shape is a property of that handler, and the two can coexist: a
         * service type may offer fixed lifecycle handlers alongside open user-named ones, which a
         * block-level flag could not say.
         *
         * @param backedByConcreteType {@code true} means the service type's own declared methods are the
         *                             handlers, so introspection already answers everything this file
         *                             could say, and {@code options} is omitted; {@code false} means
         *                             {@code options} is the only source of truth
         * @param options              the handler vocabulary; present only when
         *                             {@code backedByConcreteType} is {@code false}
         */
        public record Handlers(boolean backedByConcreteType, List<HandlerOption> options) {
        }

        /**
         * One handler in the catalog — either a named option under {@code addMode: "subset"}, or the
         * single {@link #WILDCARD_NAME} entry under {@code addMode: "many"}.
         *
         * @param name              the handler's method name, or {@link #WILDCARD_NAME} for an
         *                          open/many-shaped handler
         * @param kind              {@link #KIND_REMOTE} or {@link #KIND_RESOURCE}
         * @param addMode           spec §5.1 — {@link #ADD_MODE_SUBSET} (the reading when absent) or
         *                          {@link #ADD_MODE_MANY}. It sits on the option rather than on
         *                          {@code handlers} because the two coexist: a service type may offer fixed
         *                          lifecycle handlers alongside open user-named ones
         * @param doc               spec §5.2 — required. What this handler is for and when it fires.
         *                          Docs invert the DRY rule, and unconditionally here: {@code options}
         *                          exists only when {@code backedByConcreteType} is {@code false}, so every
         *                          handler written here has no method behind it and no doc comment to read,
         *                          making this the only description a generator will ever see
         * @param deprecated        spec §5.3 — why this handler is deprecated, as prose; {@code null} when
         *                          it is current. A deprecated handler still counts for
         *                          {@code structure.atLeastOne}: it remains legal, just not recommended
         * @param presence          meaningful only under {@code addMode: "subset"} —
         *                          {@code "required"}/{@code "optional"}. A {@code many} shape has no fixed
         *                          occurrence count to require
         * @param annotations       ids of annotations with {@code attachPoint: "function"}
         * @param returnAnnotations ids of annotations with {@code attachPoint: "return"}. Its own slot
         *                          rather than sharing {@code annotations}, because the two attach to
         *                          different syntactic positions and a consumer renders them differently
         * @param params            the handler's parameters, in meaningful positional order
         * @param returns           the handler's return type(s) — a union is expressed as more than one
         *                          element
         * @param accessor          spec §5 — the legal accessors of a {@code resource} handler. Required
         *                          for {@code kind: "resource"} and forbidden for {@code remote}. HTTP puts
         *                          its verbs here and GraphQL puts {@code get}/{@code subscribe}: the schema
         *                          names the position once rather than once per library, because both are
         *                          the same slot in the same language construct
         * @param path              spec §5 — whether a resource path is required. No syntactic form is
         *                          recorded: the language already fixes what a resource path may look like
         */
        public record HandlerOption(
                String name,
                String kind,
                String addMode,
                String doc,
                String deprecated,
                String presence,
                List<String> annotations,
                List<String> returnAnnotations,
                List<Param> params,
                List<TypeRef> returns,
                ValueSpec accessor,
                ValueSpec path) {

            public static final String KIND_REMOTE = "remote";
            public static final String KIND_RESOURCE = "resource";
            public static final String WILDCARD_NAME = "*";

            /** Spec §5.1: one fixed method name, governed by {@code presence}. The default when absent. */
            public static final String ADD_MODE_SUBSET = "subset";
            /** Spec §5.1: a shape the user instantiates any number of times, always named {@code "*"}. */
            public static final String ADD_MODE_MANY = "many";

            /** Spec §5.1 makes {@code subset} the reading for an absent {@code addMode}. */
            public boolean isMany() {
                return ADD_MODE_MANY.equals(addMode);
            }
        }

        /**
         * One parameter slot of a {@link HandlerOption}. Order in the array is meaningful and is trusted
         * to convey positional constraints.
         *
         * @param name        the parameter name to emit. Spec §7 makes this required on every fixed
         *                    slot and permits its omission only under {@code addMode: "many"}, where
         *                    the user names each occurrence: a handler in {@code options[]} has no method
         *                    behind it, so codegen renders the parameter from this entry alone, and
         *                    omitting the name does not add flexibility — it makes each generator invent
         *                    its own
         * @param doc         spec §5.2 — required. What this parameter carries; on a
         *                    {@code many} slot it describes what one occurrence is, which is the only place
         *                    that gets said
         * @param deprecated  spec §5.3 — why this parameter is deprecated, as prose; {@code null} when it
         *                    is current
         * @param type        the parameter's legal type(s) — a union is expressed as more than one
         *                    element. States the full static surface for this slot even where
         *                    {@code dataBinding} also implies it
         * @param presence    {@code "required"} or {@code "optional"} for this slot
         * @param addMode     {@link Handlers#ADD_MODE_MANY} when this slot can repeat zero or more times,
         *                    each occurrence independently named/typed by the user; {@code null} means "at
         *                    most one"
         * @param dataBinding spec §9, written inline on the parameter it describes; present only when the
         *                    raw value can be projected into a different, user-defined type
         * @param annotations ids of annotations with {@code attachPoint: "parameter"}
         */
        public record Param(
                String name,
                String doc,
                String deprecated,
                List<TypeRef> type,
                String presence,
                String addMode,
                DataBinding dataBinding,
                List<String> annotations) {
        }
    }

    /**
     * One entry in the top-level <b>{@code annotations[]}</b> registry (spec §8) — an annotation type
     * referenced elsewhere in the document, defined once here rather than restated at each attachment
     * point.
     *
     * <p>The annotation record's own field names, types, defaults and enums are never restated — they are
     * introspectable from {@link #type()} itself (the governing DRY principle). This entry carries only
     * what is not: whether the annotation must be attached at all, and where it attaches.
     *
     * <p><b>Every attach point has a precise forward reference</b>, and the construct that carries the
     * annotation is the one that names it:
     * <table><caption>attach point to referencing field</caption>
     *   <tr><td>{@code service}</td><td>{@code serviceTypes[].annotations}</td></tr>
     *   <tr><td>{@code function}</td><td>{@code handlers.options[].annotations}</td></tr>
     *   <tr><td>{@code return}</td><td>{@code handlers.options[].returnAnnotations}</td></tr>
     *   <tr><td>{@code parameter}</td><td>{@code params[].annotations}</td></tr>
     * </table>
     * This replaced the earlier reverse {@code appliesTo} list, which named service types rather than the
     * attachment site. There is deliberately no fallback path: an annotation nothing references is
     * unreachable, and the validator reports it rather than the consumer guessing where it belongs.
     *
     * @param id          referenced from whichever construct the annotation attaches to; {@code $}-prefixed
     * @param type        the annotation type; may be cross-module
     * @param attachPoint one of {@code "service"}, {@code "function"}, {@code "parameter"}, {@code "return"}
     * @param presence    {@code "required"} or {@code "optional"} — whether this annotation must be attached
     * @since 1.10.0
     */
    public record Annotation(String id, TypeRef type, String attachPoint, String presence) {

        public static final String ATTACH_POINT_SERVICE = "service";
        public static final String ATTACH_POINT_FUNCTION = "function";
        public static final String ATTACH_POINT_PARAMETER = "parameter";
        public static final String ATTACH_POINT_RETURN = "return";

        public static final String PRESENCE_REQUIRED = "required";
        public static final String PRESENCE_OPTIONAL = "optional";
    }

    /**
     * Spec §6 — a named constraint from an <b>open registry</b>. The rule is referenced, never defined
     * here: {@link #rule} names a constraint the consumer already implements, {@link #subjects} say what
     * it ranges over, and {@link #args} parameterize it. Adding a constraint is a new registry entry, not
     * a schema change.
     *
     * <p><b>Unknown ids are skipped, never fatal.</b> Spec §6: "A consumer that does not recognise a
     * {@code rule} id or a subject {@code kind} skips that rule with a logged warning and never fails."
     * That policy is what makes a new constraint kind additive, and therefore what lets §11 treat it as a
     * minor bump.
     *
     * <p><b>Placement.</b> A rule scoped to one service type lives on that {@code serviceTypes[]} entry.
     * A rule spanning different service types lives in the top-level {@link TriggerMetadataModel#rules()},
     * where every subject must name its {@link Subject#serviceType()}.
     *
     * @param id       stable and unique within the file; surfaces in the emitted diagnostic
     * @param rule     registry id, e.g. {@code "structure.exactlyOne"}
     * @param subjects what the constraint ranges over
     * @param severity {@link #SEVERITY_ERROR} (the default) or {@link #SEVERITY_WARNING}
     * @param message  diagnostic text for a consumer to surface, worded for the connector rather than
     *                 synthesised from the rule id; optional but recommended
     * @param prefer   the {@link Subject#role()} a generator should default to. A hint, not part of the
     *                 constraint
     */
    public record Rule(String id,
                       String rule,
                       List<Subject> subjects,
                       String severity,
                       String message,
                       String prefer) {

        /** Exactly one subject present — not zero, not more than one. */
        public static final String RULE_EXACTLY_ONE = "structure.exactlyOne";
        /** Zero or one subject — never more, but zero is fine. */
        public static final String RULE_AT_MOST_ONE = "structure.atMostOne";
        /** One or more subjects present. */
        public static final String RULE_AT_LEAST_ONE = "structure.atLeastOne";
        /** All subjects present, or none of them. */
        public static final String RULE_ALL_OR_NONE = "structure.allOrNone";
        /** If the {@code when} subject is present, the {@code then} subject must be present. */
        public static final String RULE_REQUIRES = "structure.requires";
        /** If the {@code when} subject is present, the {@code then} subject must be absent. */
        public static final String RULE_CONFLICTS_WITH = "structure.conflictsWith";

        /** The {@link Subject#role()} an asymmetric constraint's antecedent must carry. */
        public static final String ROLE_WHEN = "when";
        /** The {@link Subject#role()} an asymmetric constraint's consequent must carry. */
        public static final String ROLE_THEN = "then";

        public static final String SEVERITY_ERROR = "error";
        public static final String SEVERITY_WARNING = "warning";
    }

    /**
     * Spec §6.1 — what a {@link Rule} ranges over. A tagged union discriminated by {@link #kind}, so a
     * malformed subject is always distinguishable.
     *
     * <p>Modeled as one record with all shapes' fields rather than a sealed hierarchy, because Gson
     * discriminates on a field value only with a custom deserializer, and the shape check belongs to the
     * validator anyway — which reports a subject carrying the wrong fields for its kind far more usefully
     * than a parse failure would.
     *
     * <table><caption>fields populated per kind</caption>
     *   <tr><th>{@code kind}</th><th>fields</th><th>addresses</th></tr>
     *   <tr><td>{@code identifier}</td><td>none</td><td>the identifier or base-path slot</td></tr>
     *   <tr><td>{@code annotation}</td><td>{@code name}</td><td>an annotation as a whole, its presence
     *       rather than a field inside it</td></tr>
     *   <tr><td>{@code annotationField}</td><td>{@code annotation}, {@code path}</td><td>one field inside
     *       an annotation</td></tr>
     *   <tr><td>{@code handler}</td><td>{@code name}</td><td>a handler function</td></tr>
     *   <tr><td>{@code param}</td><td>{@code handler}, {@code name}</td><td>one parameter of a handler</td></tr>
     * </table>
     *
     * @param kind        the discriminator
     * @param name        for {@code annotation} an annotation id ({@code $}-prefixed); for
     *                    {@code handler} a handler name and for {@code param} a parameter name, neither of
     *                    which is an id and neither of which is prefixed
     * @param annotation  for {@code annotationField}, the annotation id the field lives in
     * @param path        for {@code annotationField}, the field path inside the annotation record. An
     *                    array, so a nested field such as {@code ["retryConfig", "maxCount"]} is reachable
     * @param handler     for {@code param}, the handler the parameter belongs to
     * @param serviceType which service type this subject belongs to; defaults to the enclosing one, and is
     *                    required in a top-level rule
     * @param role        this subject's name within its rule. Asymmetric constraints fix the names
     *                    ({@link Rule#ROLE_WHEN}/{@link Rule#ROLE_THEN}); symmetric ones use free labels,
     *                    referenced by {@link Rule#prefer()} and {@link Rule#reportOn()}
     */
    public record Subject(String kind,
                          String name,
                          String annotation,
                          List<String> path,
                          String handler,
                          String serviceType,
                          String role) {

        /** The identifier or base-path slot. */
        public static final String KIND_IDENTIFIER = "identifier";
        /** An annotation as a whole — its presence, rather than a field inside it. */
        public static final String KIND_ANNOTATION = "annotation";
        /** One field inside an annotation. */
        public static final String KIND_ANNOTATION_FIELD = "annotationField";
        /** A handler function. */
        public static final String KIND_HANDLER = "handler";
        /** One parameter of a handler. */
        public static final String KIND_PARAM = "param";
    }

    /**
     * Spec §9 — how a handler parameter's raw value may be projected into a different, user-defined type.
     *
     * <p>Written <b>inline on the parameter</b> it describes rather than in a top-level registry: a
     * binding describes one slot, so it carries no id and nothing references it. Two parameters that bind
     * the same way each state it, which costs a little repetition and keeps every parameter readable on
     * its own.
     *
     * <p>Modeled on Ballerina's {@code typedesc<T>}: a user-suppliable type, bound by an upper constraint,
     * embedded into the declared type in one or more ways.
     *
     * @param typedescs independent variants that share nothing; never empty
     */
    public record DataBinding(List<TypedescVariant> typedescs) {
    }

    /**
     * One independent variant of a {@link DataBinding}.
     *
     * @param constraint this variant's typedesc upper bound. Exactly one type, never a union — two
     *                   bounds sharing identical shapes are two variants, not one variant with two bounds
     * @param excludes   instantiations another variant already owns. Without it an envelope record such as
     *                   {@code AnydataConsumerRecord}, being itself valid {@code anydata}, would satisfy
     *                   both the {@code bare} variant and the unoverridden instantiation of the
     *                   {@code included} variant, leaving a generator no way to tell which was meant
     * @param shapes     the legal embeddings of this variant's bound
     */
    public record TypedescVariant(TypeRef constraint, List<TypeRef> excludes, List<Shape> shapes) {
    }

    /**
     * Spec §9 — how a {@link TypedescVariant}'s bound type is embedded in the declared parameter type.
     *
     * <p>Batching combines with either embedding, which is why {@code array}/{@code stream} carry an
     * {@link #element}: kafka batches both of its variants, one as an array of bare values and one as an
     * array of envelope-including records.
     *
     * @param form           {@link #FORM_BARE}, {@link #FORM_ARRAY}, {@link #FORM_STREAM} or
     *                       {@link #FORM_INCLUDED}
     * @param element        for {@code array} and {@code stream}, whether each element is
     *                       {@link #FORM_BARE} or {@link #FORM_INCLUDED}
     * @param envelope       the record the user's type includes with {@code *Envelope;}. Set for
     *                       {@code included}, and for {@code array}/{@code stream} whose {@code element}
     *                       is {@code included}
     * @param bindableFields the envelope's fields this variant's typedesc may retype; every other field
     *                       stays fixed. The complement is always derivable, so it is never restated
     * @param completionType for {@code stream}, the stream's completion type. A union — spec §9 types it
     *                       as a TypeRef-or-union so a nilable completion ({@code error?}) is written
     *                       the same way it is everywhere else: an explicit {@code ()} member
     */
    public record Shape(String form,
                        String element,
                        TypeRef envelope,
                        List<String> bindableFields,
                        List<TypeRef> completionType) {

        /** {@code T} stands alone: the declared type is the bound type, with no wrapping. */
        public static final String FORM_BARE = "bare";
        /** {@code T[]}. */
        public static final String FORM_ARRAY = "array";
        /** {@code stream<T, completionType>}. */
        public static final String FORM_STREAM = "stream";
        /** The user record does {@code *envelope;} and retypes only {@code bindableFields}. */
        public static final String FORM_INCLUDED = "included";
    }
}
