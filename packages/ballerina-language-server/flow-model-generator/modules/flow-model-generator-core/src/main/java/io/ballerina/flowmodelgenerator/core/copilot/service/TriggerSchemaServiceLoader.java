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
import io.ballerina.compiler.api.SemanticModel;
import io.ballerina.modelgenerator.commons.ModuleInfo;
import io.ballerina.modelgenerator.commons.trigger.LibraryMetadataReader;
import io.ballerina.modelgenerator.commons.trigger.models.TriggerMetadataModel;
import io.ballerina.modelgenerator.commons.trigger.models.TypeRef;
import io.ballerina.projects.Package;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Schema-driven Copilot service loader: builds a library's {@code services} JSON from exactly two
 * read-only sources instead of the SQLite service-index —
 * <ol>
 *   <li><b>{@code trigger-metadata.json}</b>, the authoring metadata: which service types exist, the
 *       handler vocabulary of marker types, parameter types/optionality, and return types;</li>
 *   <li><b>the semantic model</b> of the same resolved package the manager already compiles: listener
 *       class and init parameters, the declared methods and doc comments of concrete service types, and
 *       validation that every metadata claim actually exists in the resolved package version.</li>
 * </ol>
 *
 * <p><b>Structure.</b> This class is only an orchestrator. It resolves the document, builds the facts
 * once, pairs service types with listeners, and then runs an ordered list of components over each pair.
 * A component is a function from a read-only scope to a mutable draft, and each owns exactly one spec
 * construct — so a spec change edits one component and touches nothing else, and no component can grow a
 * dependency on another without going through {@link TriggerScope}. Substantial logic lives in a pure
 * resolver the component delegates to, which is what makes it testable without a semantic model. See
 * {@link AspectRegistry} for the ordered list.
 *
 * <p><b>Failure model.</b> Three distinct outcomes, deliberately not merged:
 * <ul>
 *   <li><b>Not a trigger library</b> — no document resolves at either tier, so an empty array is returned
 *       and the caller uses the SQLite service index. This is the overwhelming majority of libraries and is
 *       not logged.</li>
 *   <li><b>Library-level abort</b> — a document <i>was</i> found but nothing could be built from it: it
 *       declares an unimplemented spec major, it is malformed, or no listener resolves. An empty array is
 *       returned with {@code documentResolved == true}, which tells the caller <b>not</b> to substitute the
 *       index: a poorer catalog presented as authoritative hides the defect, whereas a visible absence does
 *       not. Always logged.</li>
 *   <li><b>Entry-level veto</b> — one service type or one handler is dropped with an attributable
 *       {@link Veto} while the rest of the library is served normally.</li>
 * </ul>
 *
 * <p><b>Documentation gaps this leaves.</b> A marker service type declares no methods in the library
 * source — its handler contract is enforced by a compiler plugin at user-code compile time — so no symbol
 * carries a doc comment for a handler or its parameters, and the metadata document does not model
 * descriptions. Handler and handler-parameter descriptions are therefore unavailable for marker service
 * types and are omitted, never fabricated. Concrete service types are unaffected.
 *
 * @since 1.7.0
 */
final class TriggerSchemaServiceLoader {

    private static final Logger LOGGER = Logger.getLogger(TriggerSchemaServiceLoader.class.getName());

    private static final String DEFAULT_ORG = "ballerinax";

    /**
     * Module keys for the LS-bundled {@code trigger-metadata-models/<key>/trigger-metadata.json}
     * documents, keyed by library name. This is <em>not</em> an allowlist — any library whose package
     * ships its own {@code resources/trigger-metadata.json} is served without appearing here, and a
     * library absent from this map still resolves a bundled document filed under its bare package name.
     * It maps only the libraries whose bundled document is filed under a name the library itself does not
     * carry: {@code ballerinax/mssql} maps to the {@code mssql.cdc} document — the same CDC trigger
     * published under the new module layout; its listener ({@code CdcListener}) and handler set are
     * validated against the actually resolved {@code mssql} package before use.
     */
    static final Map<String, String> BUNDLED_METADATA_KEYS = Map.of(
            "ballerinax/kafka", "kafka",
            "ballerinax/rabbitmq", "rabbitmq",
            "ballerina/ftp", "ftp",
            "ballerina/mcp", "mcp",
            "ballerinax/mssql", "mssql.cdc",
            "ballerinax/trigger.github", "trigger.github",
            // Net-new to the Copilot: these were never in the SQLite service-index.
            "ballerina/smb", "smb",
            "ballerina/websub", "websub",
            "ballerinax/trigger.google.calendar", "trigger.google.calendar");

    private TriggerSchemaServiceLoader() {
        // Prevent instantiation
    }

    /**
     * The outcome of one library load: the emitted services, and every reason an entry was dropped.
     *
     * <p>Vetoes never reach the emitted JSON — they are diagnostics about the document. They are returned
     * rather than only logged so a caller (or a test) can assert exactly what a library dropped and why,
     * which is what makes a silently missing handler impossible to reintroduce unnoticed.
     *
     * @param services         the emitted service entries, in document order
     * @param vetoes           every reason an entry was dropped, whether a service type or a single
     *                         handler
     * @param documentResolved whether a metadata document was found for this library at all, which is a
     *                         different fact from whether it produced anything. Empty-with-no-document is
     *                         the ordinary case for the overwhelming majority of libraries and means "not
     *                         a trigger library"; empty-with-a-document means the document is there and
     *                         yielded nothing, which is a defect. Only the caller can act on the
     *                         distinction, so it is reported rather than collapsed into an empty array
     */
    record LoadResult(JsonArray services, List<Veto> vetoes, boolean documentResolved) {
    }

    /**
     * Loads services for a trigger library.
     *
     * <p>Returns an empty array when inputs are missing, no metadata document resolves for the library, or
     * anything throws. Use {@link #load} instead when the caller needs to tell "no document" from
     * "document resolved and produced nothing" — this overload deliberately discards that distinction.
     *
     * @param libraryName   the library name, e.g. {@code "ballerinax/kafka"}
     * @param pkg           the resolved package the caller already compiled; may be {@code null}
     * @param semanticModel the package's semantic model; may be {@code null}
     * @return the services JSON, empty when this library is not served from metadata
     */
    static JsonArray loadServices(String libraryName, Package pkg, SemanticModel semanticModel) {
        return load(libraryName, pkg, semanticModel).services();
    }

    /**
     * {@link #loadServices} plus the veto report. Same work; this overload simply does not discard the
     * diagnostics.
     */
    static LoadResult load(String libraryName, Package pkg, SemanticModel semanticModel) {
        if (pkg == null || semanticModel == null) {
            return empty(false);
        }

        String packageName = ServiceIndexLoader.stripOrg(libraryName);
        String org = libraryName.contains("/")
                ? libraryName.substring(0, libraryName.indexOf('/'))
                : DEFAULT_ORG;

        // Flipped the moment a document is in hand, and read by the catch below: an exception thrown
        // after that point is a failure to *process* a document that exists, which the caller must not
        // mistake for "this library ships no metadata".
        boolean documentResolved = false;
        try {
            MetadataResolution resolution = resolveMetadata(libraryName, org, packageName, pkg);
            // Set from whether a document was FOUND, not from whether one was usable: a package shipping a
            // document this build refuses must not be reported as shipping none, or the caller substitutes
            // the service index for it and the refusal disappears.
            documentResolved = resolution.documentPresent();
            if (resolution.document().isEmpty()) {
                return empty(documentResolved);
            }
            TriggerMetadataModel metadata = resolution.document().get();
            if (metadata.listeners() == null || metadata.listeners().isEmpty()
                    || metadata.serviceTypes() == null || metadata.serviceTypes().isEmpty()) {
                return empty(true);
            }

            TriggerSemanticFacts facts = new TriggerSemanticFacts(semanticModel, pkg);
            ListenerPairingResolver.Pairings paired = ListenerPairingResolver.resolveWithDiagnostics(
                    metadata.listeners(), metadata.serviceTypes(), facts);
            List<ListenerPairingResolver.ListenerPairing> pairings = paired.pairings();
            if (pairings.isEmpty()) {
                // An unresolvable listener means the resolved package no longer matches the metadata's
                // world view — abort the library so the caller falls back, rather than emitting a
                // listener the generated code could not instantiate.
                TypeRef declared = metadata.listeners().get(0).type();
                LOGGER.warning("No listener class resolvable for " + libraryName
                        + " (metadata declared: " + (declared == null ? null : declared.name()) + ")");
                return new LoadResult(new JsonArray(), paired.vetoes(), true);
            }

            AspectRegistry registry = new AspectRegistry();
            // Spec §8's registry is built once per library: it is shared by every service type, and by
            // every attach point once the later phases land.
            AnnotationRegistry annotations = AnnotationRegistry.of(metadata);
            JsonArray services = new JsonArray();
            // Seeded with the pairing tier's own drops. A service type whose listener did not resolve never
            // reaches `buildService`, so its reason has nowhere else to come from — and a PARTIAL pairing
            // failure used to be entirely silent, since the log line above fires only when every one fails.
            List<Veto> vetoes = new ArrayList<>(paired.vetoes());

            for (ListenerPairingResolver.ListenerPairing pairing : pairings) {
                ServiceDraft draft = buildService(libraryName, org, packageName, metadata, annotations,
                        pairing, facts, registry);
                vetoes.addAll(draft.vetoes());
                if (draft.isVetoed()) {
                    continue;
                }
                services.add(draft.toJson());
            }

            for (Veto veto : vetoes) {
                LOGGER.warning("Dropped for " + libraryName + ": " + veto);
            }
            return new LoadResult(services, vetoes, true);
        } catch (RuntimeException e) {
            LOGGER.warning("Failed to load schema-driven services for " + libraryName + ": " + e.getMessage());
            return empty(documentResolved);
        }
    }

    /** Runs the ordered service components over one (service type × listener) pair. */
    private static ServiceDraft buildService(String libraryName, String org, String packageName,
                                             TriggerMetadataModel metadata, AnnotationRegistry annotations,
                                             ListenerPairingResolver.ListenerPairing pairing,
                                             TriggerSemanticFacts facts, AspectRegistry registry) {
        TriggerScope scope = new TriggerScope(
                libraryName,
                org,
                packageName,
                ServiceIdentityResolver.homeModule(pairing.listener(), packageName),
                metadata,
                annotations,
                pairing.serviceType(),
                pairing.listener(),
                pairing.listenerClass(),
                facts,
                facts::declaresType);

        ServiceDraft draft = new ServiceDraft();
        for (BiConsumer<TriggerScope, ServiceDraft> aspect : registry.serviceAspects()) {
            aspect.accept(scope, draft);
            if (draft.isVetoed()) {
                // A vetoed entry is dropped whole; running the remaining components would build output
                // nothing will read.
                break;
            }
        }
        return draft;
    }

    private static LoadResult empty(boolean documentResolved) {
        return new LoadResult(new JsonArray(), List.of(), documentResolved);
    }

    /**
     * Resolves the trigger metadata document for a library, preferring the one the connector ships itself
     * over the LS-bundled copy.
     *
     * <p>The connector's own document is versioned with the connector, so it can never describe a release
     * the resolved package predates — the bundled mcp document declaring {@code StreamableHttpListener}
     * before mcp 1.2.0 shipped it is exactly that failure mode. It also means a connector published after
     * this LS is served without an LS release. The bundled tier then covers the libraries that do not ship
     * a document yet.
     *
     * <p>Reading the shipped document costs a single {@code stat} against the already-resolved package, so
     * consulting it for every library (rather than a curated few) is cheap.
     */
    private static MetadataResolution resolveMetadata(String libraryName, String org,
                                                      String packageName, Package pkg) {
        LibraryMetadataReader reader = LibraryMetadataReader.getInstance();
        String metadataKey = BUNDLED_METADATA_KEYS.getOrDefault(libraryName, packageName);
        return decideMetadata(libraryName, reader.readShippedTriggerMetadata(pkg),
                () -> reader.getPackagedTriggerMetadataModel(
                        new ModuleInfo(org, packageName, metadataKey, null)));
    }

    /**
     * The two-tier precedence rule, as a pure function of what the connector shipped.
     *
     * <p>Split out from {@link #resolveMetadata} so it can be tested: reaching it through a {@link Package}
     * would need a published connector that ships a document, and none exists yet — which is exactly how the
     * rule below came to be wrong without any test noticing.
     *
     * @param libraryName the library, for the log line
     * @param shipped     what reading the connector's own document produced
     * @param bundled     the LS-bundled document for this library, consulted only when the connector ships
     *                    none at all
     * @return the document to use, and whether one was present at either tier
     */
    static MetadataResolution decideMetadata(String libraryName,
                                             LibraryMetadataReader.MetadataRead shipped,
                                             Supplier<Optional<TriggerMetadataModel>> bundled) {
        if (shipped.usable().isPresent()) {
            return new MetadataResolution(shipped.usable(), true);
        }
        if (shipped.present()) {
            // The connector ships a document this build cannot read — an unimplemented major version, or a
            // malformed file. The LS's bundled copy is NOT a substitute for it: it is an OLDER description of
            // the same connector, so serving it would answer a v2 package with a v1 contract and present the
            // result as authoritative. That is precisely the "confident-looking downgrade" this loader's own
            // fallback policy refuses to prefer over a visible absence, and the shipped-first precedence in
            // this method exists to prevent it.
            //
            // `documentResolved` stays true, so the caller does not silently substitute the SQLite index
            // either: the library renders its curated overlay and logs why, which is findable.
            LOGGER.warning("Not falling back to the bundled trigger metadata for " + libraryName
                    + ": the package ships its own document and it is " + shipped.outcome()
                    + ". The bundled copy describes an earlier release, so serving it would state a"
                    + " contract this package version does not honour.");
            return new MetadataResolution(Optional.empty(), true);
        }
        Optional<TriggerMetadataModel> fromBundle = bundled.get();
        return new MetadataResolution(fromBundle, fromBundle.isPresent());
    }

    /**
     * A resolved document, and whether one was there at all.
     *
     * <p>{@code documentPresent} is not {@code document.isPresent()}: a connector that ships a document this
     * build refuses is a library <i>with</i> metadata that yielded nothing, which the caller must not treat
     * as a library without any.
     *
     * @param document        the usable document, or empty
     * @param documentPresent whether a document was found, whatever came of reading it
     */
    record MetadataResolution(Optional<TriggerMetadataModel> document, boolean documentPresent) {
    }

}
