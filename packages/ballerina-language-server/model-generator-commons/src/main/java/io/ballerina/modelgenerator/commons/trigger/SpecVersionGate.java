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

import io.ballerina.modelgenerator.commons.trigger.models.TriggerMetadataModel;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Owns the spec's <b>top-level {@code version} key</b>: whether a {@code trigger-metadata.json} document
 * may be read at all.
 *
 * <h2>Spec §11's two-component scheme</h2>
 *
 * <p>The version has the form {@code v<major>.<minor>}, and §11 assigns the two components opposite
 * consumer obligations:
 *
 * <ul>
 *   <li><b>minor — "keep reading, skip what it does not recognise".</b> A minor bump is purely additive, so
 *       every key this build understands still means the same thing. A {@code v1.5} document read by a
 *       {@code v1.3} consumer is legal and is <i>expected</i> to contain fields the consumer ignores. The
 *       minor component is therefore never compared: gating on it would refuse documents §11.3 explicitly
 *       requires be read.</li>
 *   <li><b>major — "refuse the instance".</b> A major bump means a field was renamed, removed, re-nested,
 *       retyped, or changed meaning, so the same keys no longer mean the same things. Reading one with the
 *       wrong semantics is the genuinely dangerous outcome: it yields confident, wrong API guidance rather
 *       than an obvious absence. Refusal is cheap — the caller degrades to the SQLite service index, a
 *       poorer catalog rather than a wrong one.</li>
 * </ul>
 *
 * <h2>Why a bare {@code "v1"} is accepted</h2>
 *
 * <p>{@code "v1"} is the pre-release form every document authored before §11 existed carries, and it does
 * not match {@code v<major>.<minor>}. It is read as {@code v1.0} with a warning rather than refused.
 *
 * <p>This is a deliberate, and deliberately generous, reading. Strictly, the m1-era documents that carry
 * {@code "v1"} are <i>structurally</i> different from {@code v1.0} — {@code appliesTo},
 * {@code dataBindingRules} and {@code multipleServicesPerListenerAllowed} were removed or re-nested — so by
 * §11.2's own taxonomy that transition is a major change, and §11.5 exempts it only because the spec was
 * unreleased at the time. A consumer could justifiably refuse. The cost of refusing, though, is taking a
 * working library offline over a version string, whereas the cost of accepting is that a stale document's
 * removed keys are ignored — which is exactly what the omission rule already does with any absent key. The
 * warning is what keeps that visible.
 *
 * <h2>Why an absent version is accepted</h2>
 *
 * <p>Same reasoning, one step further: an absent version states nothing at all, so it cannot be a version
 * this build fails to implement. The corresponding validator check reports the omission as an ERROR against
 * the corpus, so the two are not in tension — the gate keeps <i>runtime</i> permissive while the validator
 * keeps <i>the repo's own documents</i> strict.
 *
 * @since 1.10.0
 */
public final class SpecVersionGate {

    /** The major version this build implements. */
    public static final int MAJOR_V1 = 1;

    /** The canonical version this build implements, as it should be written in a document. */
    public static final String VERSION_V1 = "v1.0";

    /**
     * The pre-release form, which predates spec §11's {@code v<major>.<minor>} scheme. Read as
     * {@link #VERSION_V1} with a warning; see the class javadoc for why it is not refused.
     */
    public static final String VERSION_PRERELEASE_V1 = "v1";

    /** Spec §11: "{@code version} has the form {@code v<major>.<minor>} ... There is no patch component." */
    private static final Pattern VERSION = Pattern.compile("^v(\\d+)\\.(\\d+)$");

    private SpecVersionGate() {
        // Prevent instantiation
    }

    /** What a caller must do with a document, given the version it declares. */
    public enum VersionVerdict {
        /** The document declares a major version this build implements; read it. */
        ACCEPT,
        /** The document declares no version, or the pre-release form; read it as v1.0 and say so. */
        ACCEPT_WITH_WARNING,
        /** The document declares a major version this build does not implement; do not read it. */
        REJECT;

        /**
         * Whether the document may be read.
         *
         * @return whether a caller may use the document
         */
        public boolean isUsable() {
            return this != REJECT;
        }
    }

    /**
     * Evaluates a declared version.
     *
     * <p>A blank version is treated as absent rather than as an unknown value. It states nothing, so it
     * cannot be a version this build fails to implement, and the permissive reading is the one that cannot
     * take a working library offline over a formatting slip.
     *
     * <p>A well-formed version with an unimplemented <b>major</b> is refused. A malformed one — {@code "1"},
     * {@code "V1.0"}, {@code "v1.0.0"} — is refused too: it is not the pre-release form and not a version,
     * so there is no reading of it that this build can claim to implement.
     *
     * @param documentVersion the document's declared {@code version}; may be {@code null}
     * @return the verdict
     */
    public static VersionVerdict evaluate(String documentVersion) {
        if (documentVersion == null || documentVersion.isBlank()) {
            return VersionVerdict.ACCEPT_WITH_WARNING;
        }
        String version = documentVersion.trim();
        if (VERSION_PRERELEASE_V1.equals(version)) {
            return VersionVerdict.ACCEPT_WITH_WARNING;
        }
        Matcher matcher = VERSION.matcher(version);
        if (!matcher.matches()) {
            return VersionVerdict.REJECT;
        }
        // Only the major is compared. Spec §11.3: a v1.5 instance read by a v1.3 consumer must be read, so
        // a minor this build has never heard of is not a reason to refuse anything.
        try {
            return Integer.parseInt(matcher.group(1)) == MAJOR_V1
                    ? VersionVerdict.ACCEPT : VersionVerdict.REJECT;
        } catch (NumberFormatException e) {
            // A major too large for an int is certainly not the one this build implements.
            return VersionVerdict.REJECT;
        }
    }

    /**
     * {@link #evaluate(String)} for a parsed document. A {@code null} document has nothing to gate and is
     * reported as acceptable; the caller's own empty-check is what handles it.
     *
     * @param document the parsed document; may be {@code null}
     * @return the verdict
     */
    public static VersionVerdict evaluate(TriggerMetadataModel document) {
        return document == null ? VersionVerdict.ACCEPT_WITH_WARNING : evaluate(document.version());
    }
}
