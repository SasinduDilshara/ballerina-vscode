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

import io.ballerina.modelgenerator.commons.trigger.models.TriggerMetadataModel;

import java.util.ArrayList;
import java.util.List;

/**
 * Owns <b>spec §2.1 {@code listeners[].platformDependencies}</b>: native artifacts the build cannot fetch.
 *
 * <p>The sibling of {@link RequiredImportResolver}, and for the same underlying reason: both describe a
 * dependency that <b>nothing in the generated code references by name</b>, so no other part of the pipeline
 * can discover it. A {@code requiredImport} is a Ballerina package; a platform dependency is a jar — and in
 * the case §2.1 was written for, one whose licence forbids publishing it to any repository a build can
 * reach.
 *
 * <p><b>Native libraries are the half that fails silently.</b> A missing jar is a compile error, which is
 * loud. A missing native library is not: the package compiles, and the service fails at run time when the
 * JVM tries to load it. Nothing in the build graph records that requirement, which is exactly the gap this
 * metadata exists to fill, so the OS-specific entries are carried through rather than summarized away.
 *
 * @since 1.10.0
 */
final class PlatformDependencyResolver {

    private PlatformDependencyResolver() {
        // Prevent instantiation
    }

    /**
     * One artifact the generated project needs on its classpath.
     *
     * @param coordinate     the Maven coordinate as {@code groupId:artifactId:version}
     * @param provided       whether spec §2.1's {@code scope: "provided"} applies, i.e. the jar is
     *                       compile-time only and must be supplied by the deployment rather than bundled
     * @param acquisitionUrl where to obtain it; {@code null} when the document states none
     * @param acquisitionNote the human instructions, which is the part that actually identifies which
     *                       artifact to download; {@code null} when the document states none
     * @param nativeLibraries the per-OS libraries that must be loadable at run time, in document order
     */
    record PlatformDependency(String coordinate,
                              boolean provided,
                              String acquisitionUrl,
                              String acquisitionNote,
                              List<NativeLibrary> nativeLibraries) {
    }

    /**
     * One OS-specific native library.
     *
     * @param os       {@code linux}, {@code windows} or {@code macos}
     * @param file     the library file name
     * @param variable the environment variable that OS discovers it through. Derived here rather than
     *                 carried in the document, exactly as spec §2.1 states it once in prose: "Where the
     *                 library must go is determined by {@code os}, so it is stated once here instead of on
     *                 every entry". A consumer that omitted it would leave the reader knowing what to
     *                 download and not where to put it
     */
    record NativeLibrary(String os, String file, String variable) {
    }

    /**
     * Resolves a listener's declared platform dependencies, in document order.
     *
     * <p>An entry with no usable coordinate is skipped: the coordinate is the whole content of the entry,
     * and a note pointing at an artifact it cannot name states nothing actionable.
     *
     * @param listener the document's listener; may be {@code null}
     * @return one entry per usable dependency; empty when there are none
     */
    static List<PlatformDependency> resolve(TriggerMetadataModel.Listener listener) {
        List<PlatformDependency> resolved = new ArrayList<>();
        if (listener == null || listener.platformDependencies() == null) {
            return resolved;
        }
        for (TriggerMetadataModel.PlatformDependency dependency : listener.platformDependencies()) {
            if (dependency == null) {
                continue;
            }
            String coordinate = coordinate(dependency);
            if (coordinate == null) {
                continue;
            }
            TriggerMetadataModel.Acquisition acquisition = dependency.acquisition();
            resolved.add(new PlatformDependency(
                    coordinate,
                    TriggerMetadataModel.PlatformDependency.SCOPE_PROVIDED.equals(dependency.scope()),
                    acquisition == null ? null : blankToNull(acquisition.url()),
                    acquisition == null ? null : blankToNull(acquisition.note()),
                    nativeLibraries(dependency)));
        }
        return resolved;
    }

    private static List<NativeLibrary> nativeLibraries(
            TriggerMetadataModel.PlatformDependency dependency) {
        List<NativeLibrary> libraries = new ArrayList<>();
        if (dependency.nativeLibraries() == null) {
            return libraries;
        }
        for (TriggerMetadataModel.NativeLibrary library : dependency.nativeLibraries()) {
            if (library == null || library.os() == null || library.file() == null) {
                continue;
            }
            libraries.add(new NativeLibrary(library.os(), library.file(), discoveryVariable(library.os())));
        }
        return libraries;
    }

    /** Spec §2.1's os-to-variable table. Unknown OS yields {@code null} rather than a guessed variable. */
    private static String discoveryVariable(String os) {
        return switch (os) {
            case TriggerMetadataModel.NativeLibrary.OS_LINUX -> "LD_LIBRARY_PATH";
            case TriggerMetadataModel.NativeLibrary.OS_WINDOWS -> "PATH";
            case TriggerMetadataModel.NativeLibrary.OS_MACOS -> "DYLD_LIBRARY_PATH";
            default -> null;
        };
    }

    private static String coordinate(TriggerMetadataModel.PlatformDependency dependency) {
        String group = blankToNull(dependency.groupId());
        String artifact = blankToNull(dependency.artifactId());
        if (group == null || artifact == null) {
            return null;
        }
        String version = blankToNull(dependency.version());
        return version == null ? group + ":" + artifact : group + ":" + artifact + ":" + version;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
