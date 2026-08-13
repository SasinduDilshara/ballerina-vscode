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

package io.ballerina.modelgenerator.commons.trigger;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import io.ballerina.modelgenerator.commons.ModuleInfo;
import io.ballerina.modelgenerator.commons.PackageUtil;
import io.ballerina.modelgenerator.commons.trigger.models.TriggerMetadataModel;
import io.ballerina.modelgenerator.commons.trigger.models.TriggerUISchemaModel;
import io.ballerina.modelgenerator.commons.trigger.utils.TriggerMetadataGson;
import io.ballerina.projects.Package;
import io.ballerina.projects.PackageDescriptor;
import io.ballerina.projects.PackageName;
import io.ballerina.projects.PackageOrg;
import io.ballerina.projects.PackageVersion;
import io.ballerina.projects.environment.PackageRepository;
import io.ballerina.projects.environment.ResolutionOptions;
import io.ballerina.projects.environment.ResolutionRequest;
import io.ballerina.projects.internal.environment.BallerinaUserHome;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Connector-agnostic entry point for reading the trigger model family, shared by every LS extension.
 */
public final class LibraryMetadataReader {

    private static final Logger LOGGER = Logger.getLogger(LibraryMetadataReader.class.getName());

    private static final String TRIGGER_METADATA_RESOURCE_PATH = "resources/trigger-metadata.json";
    private static final String TRIGGER_UI_SCHEMA_RESOURCE_PATH = "resources/trigger-ui-schema.json";
    private static final String PACKAGED_TRIGGER_METADATA_ROOT = "trigger-metadata-models";
    private static final String PACKAGED_TRIGGER_METADATA_FILE = "trigger-metadata.json";
    private static final int MAX_CACHE_SIZE = 2;

    private static final Duration PACKAGE_ROOT_CACHE_TTL = Duration.ofSeconds(60);

    private static final LibraryMetadataReader INSTANCE = new LibraryMetadataReader();

    private final Cache<String, Optional<Path>> packageRootCache =
            Caffeine.newBuilder().maximumSize(MAX_CACHE_SIZE).expireAfterWrite(PACKAGE_ROOT_CACHE_TTL).build();
    private final Cache<String, Optional<TriggerMetadataModel>> packagedMetadataCache =
            Caffeine.newBuilder().maximumSize(MAX_CACHE_SIZE).build();

    private final Gson plainGson = new Gson();

    private LibraryMetadataReader() {
    }

    public static LibraryMetadataReader getInstance() {
        return INSTANCE;
    }

    /**
     * Why a read produced no usable document — three failures, not one absence.
     *
     * <p>Collapsing them into an empty {@link Optional} is what let two real defects hide. A caller that
     * cannot tell "this package ships no metadata" from "this package ships metadata I must not read" will
     * substitute something else for the latter, and the something else is always worse than nothing: a
     * <b>stale bundled copy</b> describing a release the package no longer matches, or a poorer catalog
     * presented with no hint that a richer one was rejected. Only the caller can decide what to do, so the
     * distinction is reported rather than swallowed.
     */
    public enum MetadataOutcome {
        /** The package ships no {@code resources/trigger-metadata.json} at all. */
        ABSENT,
        /** A document was read and this build implements its version. */
        USABLE,
        /** A document is present, but declares a spec major version this build does not implement. */
        UNSUPPORTED_VERSION,
        /** A document is present, but could not be parsed. */
        MALFORMED
    }

    /**
     * One document read, with the reason when there is nothing usable.
     *
     * @param document the parsed document, or {@code null} unless {@code outcome} is
     *                 {@link MetadataOutcome#USABLE}
     * @param outcome  what happened
     */
    public record MetadataRead(TriggerMetadataModel document, MetadataOutcome outcome) {

        private static final MetadataRead ABSENT = new MetadataRead(null, MetadataOutcome.ABSENT);

        static MetadataRead absent() {
            return ABSENT;
        }

        static MetadataRead of(TriggerMetadataModel document) {
            return new MetadataRead(document, MetadataOutcome.USABLE);
        }

        static MetadataRead failed(MetadataOutcome outcome) {
            return new MetadataRead(null, outcome);
        }

        /** The document when it may be used, so an indifferent caller keeps its one-liner. */
        public Optional<TriggerMetadataModel> usable() {
            return Optional.ofNullable(document);
        }

        /**
         * Whether a document was <b>there</b>, whatever came of reading it.
         *
         * <p>This is the question a fallback has to ask: a package that ships a document it cannot serve
         * must not be quietly served someone else's.
         *
         * @return whether the package ships a document
         */
        public boolean present() {
            return outcome != MetadataOutcome.ABSENT;
        }
    }

    /** The connector's own {@code resources/trigger-metadata.json}, resolved from its {@code .bala}. */
    public Optional<TriggerMetadataModel> getTriggerMetadataModel(ModuleInfo moduleInfo) {
        return packageRoot(moduleInfo).map(this::readTriggerMetadataModel).flatMap(MetadataRead::usable);
    }

    /**
     * The connector's own {@code resources/trigger-metadata.json}, read from a package the caller has
     * already resolved, so no second — and potentially network-bound — resolution is paid.
     *
     * @param pkg the already-resolved package (may be {@code null})
     * @return the parsed document, or empty when the package ships none
     */
    public Optional<TriggerMetadataModel> getShippedTriggerMetadataModel(Package pkg) {
        return readShippedTriggerMetadata(pkg).usable();
    }

    /**
     * {@link #getShippedTriggerMetadataModel(Package)} plus <i>why</i> there is no usable document.
     *
     * <p>Use this wherever an empty result would otherwise be answered by substituting a different document.
     * A connector shipping its own metadata is versioned with itself and therefore authoritative: if its
     * document cannot be read, no other document describes the release the caller actually resolved, and
     * quietly serving the LS's bundled copy states a contract the package no longer honours.
     *
     * @param pkg the already-resolved package (may be {@code null})
     * @return the read, never {@code null}
     */
    public MetadataRead readShippedTriggerMetadata(Package pkg) {
        if (pkg == null) {
            return MetadataRead.absent();
        }
        try {
            return readTriggerMetadataModel(pkg.project().sourceRoot());
        } catch (Throwable e) {
            // ABSENT, not MALFORMED: readTriggerMetadataModel already classifies the two outcomes that
            // describe the document itself, so anything reaching here failed BEFORE the content was ever in
            // question. Getting that wrong is expensive — MALFORMED makes present() true, which tells the
            // caller to suppress both the bundled document and the service index.
            LOGGER.warning("Could not read " + TRIGGER_METADATA_RESOURCE_PATH + " from "
                    + pkg.packageOrg().value() + "/" + pkg.packageName().value()
                    + "; treating the package as shipping no metadata: " + e);
            return MetadataRead.absent();
        }
    }

    /** The connector's own {@code resources/trigger-ui-schema.json}, resolved from its {@code .bala}. */
    public Optional<TriggerUISchemaModel> getTriggerUISchemaModel(ModuleInfo moduleInfo) {
        return packageRoot(moduleInfo).flatMap(this::readTriggerUISchemaModel);
    }

    /** Whether the connector's {@code .bala} is present in the local repository. */
    public boolean isLocallyResolvable(ModuleInfo moduleInfo) {
        return packageRoot(moduleInfo).isPresent();
    }

    /**
     * The LS's bundled {@code trigger-metadata-models/<moduleName>/trigger-metadata.json} classpath
     * resource, if any.
     */
    public Optional<TriggerMetadataModel> getPackagedTriggerMetadataModel(ModuleInfo moduleInfo) {
        if (moduleInfo == null || moduleInfo.moduleName() == null) {
            return Optional.empty();
        }
        return packagedMetadataCache.get(moduleInfo.moduleName(), this::readPackagedMetadata);
    }

    /**
     * The connector's own {@code resources/trigger-metadata.json}, resolved from the Ballerina
     * <b>local</b> repository rather than Central.
     */
    public Optional<TriggerMetadataModel> getTriggerMetadataModelFromLocalRepository(ModuleInfo moduleInfo) {
        return localPackageRoot(moduleInfo).map(this::readTriggerMetadataModel)
                .flatMap(MetadataRead::usable);
    }

    /** The connector's own {@code resources/trigger-ui-schema.json}, resolved from the local repository. */
    public Optional<TriggerUISchemaModel> getTriggerUISchemaModelFromLocalRepository(ModuleInfo moduleInfo) {
        return localPackageRoot(moduleInfo).flatMap(this::readTriggerUISchemaModel);
    }

    /** Every {@code org/name/version} present in the Ballerina local repository, as {@link ModuleInfo}. */
    public List<ModuleInfo> listLocalRepositoryModules() {
        List<ModuleInfo> modules = new ArrayList<>();
        try {
            Map<String, List<String>> packagesByOrg = localRepository().getPackages();
            for (Map.Entry<String, List<String>> entry : packagesByOrg.entrySet()) {
                String org = entry.getKey();
                for (String nameAndVersion : entry.getValue()) {
                    String[] parts = nameAndVersion.split(":");
                    if (parts.length != 2) {
                        continue;
                    }
                    modules.add(new ModuleInfo(org, parts[0], parts[0], parts[1]));
                }
            }
        } catch (Throwable e) {
            LOGGER.log(Level.FINE, "Listing local-repository modules failed", e);
            return List.of();
        }
        return modules;
    }

    /**
     * The connector's compiled {@link Package}, resolved via the local repository. Deliberately not
     * cached, unlike {@link #packageRoot}.
     */
    public Optional<Package> getCompiledPackageFromLocalRepository(ModuleInfo moduleInfo) {
        if (moduleInfo == null || !moduleInfo.isComplete()) {
            return Optional.empty();
        }
        try {
            PackageDescriptor descriptor = PackageDescriptor.from(
                    PackageOrg.from(moduleInfo.org()), PackageName.from(moduleInfo.packageName()),
                    PackageVersion.from(moduleInfo.version()));
            ResolutionRequest request = ResolutionRequest.from(descriptor);
            return localRepository().getPackage(request, ResolutionOptions.builder().setOffline(true).build());
        } catch (Throwable e) {
            LOGGER.log(Level.FINE, "Compiling local-repository package failed for "
                    + moduleInfo.org() + "/" + moduleInfo.packageName(), e);
            return Optional.empty();
        }
    }

    /** {@code Path}-rooted counterpart of {@link #getCompiledPackageFromLocalRepository}. */
    private Optional<Path> localPackageRoot(ModuleInfo moduleInfo) {
        return getCompiledPackageFromLocalRepository(moduleInfo).map(pkg -> pkg.project().sourceRoot());
    }

    /** The Ballerina local repository handle, resolved once and cached. */
    private PackageRepository localRepository() {
        return LocalRepositoryHolder.INSTANCE;
    }

    private static final class LocalRepositoryHolder {
        private static final PackageRepository INSTANCE = BallerinaUserHome.from(
                PackageUtil.getSampleProject().projectEnvironmentContext().environment()).localPackageRepository();
    }

    private Optional<TriggerMetadataModel> readPackagedMetadata(String moduleName) {
        String resourcePath = PACKAGED_TRIGGER_METADATA_ROOT + "/" + moduleName + "/"
                + PACKAGED_TRIGGER_METADATA_FILE;
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                return Optional.empty();
            }
            String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            return gated(TriggerMetadataGson.instance().fromJson(json, TriggerMetadataModel.class),
                    resourcePath).usable();
        } catch (IOException | JsonParseException e) {
            // A bundled document is this repo's own and TriggerMetadataCorpusTest validates every one, so a
            // failure here is a build defect. Logged all the same: silence is what made the shipped-document
            // equivalent undiagnosable.
            LOGGER.warning("Ignoring bundled " + resourcePath + ": " + e);
            return Optional.empty();
        }
    }

    /**
     * Applies the spec's top-level {@code version} gate to a freshly-parsed document.
     *
     * <p>A rejected document reports {@link MetadataOutcome#UNSUPPORTED_VERSION} rather than an absence,
     * because those two demand opposite things of a caller: an absence may be filled from elsewhere, a
     * rejection may not. The log line deliberately does not claim what the caller will do.
     *
     * @param document the parsed document; may be {@code null}
     * @param source   what was read, for the log line
     * @return the read outcome
     */
    private MetadataRead gated(TriggerMetadataModel document, String source) {
        if (document == null) {
            LOGGER.warning("Ignoring " + source + ": it parsed to no document.");
            return MetadataRead.failed(MetadataOutcome.MALFORMED);
        }
        SpecVersionGate.VersionVerdict verdict = SpecVersionGate.evaluate(document);
        if (verdict == SpecVersionGate.VersionVerdict.REJECT) {
            LOGGER.warning("Ignoring " + source + ": it declares spec version '" + document.version()
                    + "', which this build does not implement (expected major "
                    + SpecVersionGate.MAJOR_V1 + ", e.g. '" + SpecVersionGate.VERSION_V1 + "').");
            return MetadataRead.failed(MetadataOutcome.UNSUPPORTED_VERSION);
        }
        if (verdict == SpecVersionGate.VersionVerdict.ACCEPT_WITH_WARNING) {
            LOGGER.fine(() -> source + " declares no spec `version`; reading it as '"
                    + SpecVersionGate.VERSION_V1 + "'.");
        }
        return MetadataRead.of(document);
    }

    /**
     * Resolves and parses {@code resources/trigger-metadata.json} relative to {@code packageRoot}.
     *
     * <p>Package-private rather than private, purely as a test seam: no package published to Central ships
     * this file yet, so a test going in through {@link Package} would have nothing to read — which is why
     * the shipped-document path had no test at all before.
     */
    MetadataRead readTriggerMetadataModel(Path packageRoot) {
        Optional<String> json = readResourceFile(packageRoot, TRIGGER_METADATA_RESOURCE_PATH);
        if (json.isEmpty()) {
            return MetadataRead.absent();
        }
        String source = packageRoot.resolve(TRIGGER_METADATA_RESOURCE_PATH).toString();
        try {
            return gated(TriggerMetadataGson.instance().fromJson(json.get(), TriggerMetadataModel.class),
                    source);
        } catch (JsonParseException e) {
            // Logged, not silent: this is the one signal a connector author gets that their file has a typo.
            LOGGER.warning("Ignoring " + source + ": it is not valid trigger metadata: " + e.getMessage());
            return MetadataRead.failed(MetadataOutcome.MALFORMED);
        }
    }

    private Optional<TriggerUISchemaModel> readTriggerUISchemaModel(Path packageRoot) {
        return readResourceFile(packageRoot, TRIGGER_UI_SCHEMA_RESOURCE_PATH).flatMap(json -> {
            try {
                return Optional.ofNullable(plainGson.fromJson(json, TriggerUISchemaModel.class));
            } catch (JsonParseException e) {
                return Optional.empty();
            }
        });
    }

    /** The local {@code .bala} root of {@code moduleInfo}. Only a hit is memoized. */
    private Optional<Path> packageRoot(ModuleInfo moduleInfo) {
        if (moduleInfo == null || moduleInfo.org() == null || moduleInfo.moduleName() == null) {
            return Optional.empty();
        }
        String key = moduleInfo.org() + "/" + moduleInfo.moduleName();
        Optional<Path> cached = packageRootCache.getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        Optional<Path> resolved = resolvePackageRoot(moduleInfo);
        if (resolved.isPresent()) {
            packageRootCache.put(key, resolved);
        }
        return resolved;
    }

    private Optional<Path> resolvePackageRoot(ModuleInfo moduleInfo) {
        try {
            Optional<Package> pkg = PackageUtil.getModulePackageOffline(PackageUtil.getSampleProject(),
                    moduleInfo.org(), moduleInfo.moduleName());
            return pkg.map(aPackage -> aPackage.project().sourceRoot());
        } catch (Throwable e) {
            return Optional.empty();
        }
    }

    /** Reads a package-relative file as UTF-8 text, guarding against it escaping {@code packageRoot}. */
    private Optional<String> readResourceFile(Path packageRoot, String relativePath) {
        Path file = packageRoot.resolve(relativePath).normalize();
        if (!file.startsWith(packageRoot) || !Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
