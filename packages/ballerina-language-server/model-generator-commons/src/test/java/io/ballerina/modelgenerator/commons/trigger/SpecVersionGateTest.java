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
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Conformance tests for the spec's top-level {@code version} key, written against the spec text.
 *
 * @since 1.10.0
 */
public class SpecVersionGateTest {

    @Test
    public void testTheImplementedVersionIsAccepted() {
        // Spec §11: "`version` has the form v<major>.<minor>, such as "v1.0"."
        Assert.assertEquals(SpecVersionGate.evaluate("v1.0"), SpecVersionGate.VersionVerdict.ACCEPT);
    }

    @Test
    public void testAnyMinorOfTheImplementedMajorIsAccepted() {
        // Spec §11.3: a consumer built for v1.3 must READ a v1.5 instance, skipping what it does not
        // recognise. Gating on the minor would refuse documents the spec requires be read, so the minor is
        // never compared — not even a minor far ahead of this build.
        Assert.assertEquals(SpecVersionGate.evaluate("v1.5"), SpecVersionGate.VersionVerdict.ACCEPT);
        Assert.assertEquals(SpecVersionGate.evaluate("v1.99"), SpecVersionGate.VersionVerdict.ACCEPT);
        // And the other direction: "a minor bump never removes anything", so an older minor is readable too.
        Assert.assertEquals(SpecVersionGate.evaluate("v1.0"), SpecVersionGate.VersionVerdict.ACCEPT);
    }

    @Test
    public void testThePreReleaseFormIsAcceptedWithAWarning() {
        // "v1" predates §11's two-component scheme. Strictly the m1-era documents carrying it are
        // structurally different from v1.0, but refusing would take a working library offline over a
        // version string, whereas accepting only means a removed key is ignored — which is what the
        // omission rule does with any absent key anyway. The warning keeps that visible, and VersionCheck
        // reports it as an ERROR against this repo's own corpus so the migration still finishes.
        Assert.assertEquals(SpecVersionGate.evaluate("v1"),
                SpecVersionGate.VersionVerdict.ACCEPT_WITH_WARNING);
        Assert.assertTrue(SpecVersionGate.evaluate("v1").isUsable());
    }

    @Test
    public void testAnAbsentVersionIsAcceptedWithAWarningRatherThanRejected() {
        // Every document predates the key. Rejecting would disable every trigger library at once, turning
        // a forward-compatibility guard into an outage — so the runtime gate stays permissive and the
        // corpus test is what keeps this repo's own documents honest.
        Assert.assertEquals(SpecVersionGate.evaluate((String) null),
                SpecVersionGate.VersionVerdict.ACCEPT_WITH_WARNING);
        Assert.assertTrue(SpecVersionGate.evaluate((String) null).isUsable());
    }

    @Test
    public void testABlankVersionIsReadAsAbsentRatherThanUnknown() {
        // A blank string states nothing, so it cannot be a version this build fails to implement. The
        // permissive reading is the one that cannot take a working library offline over a formatting slip.
        Assert.assertEquals(SpecVersionGate.evaluate("   "),
                SpecVersionGate.VersionVerdict.ACCEPT_WITH_WARNING);
    }

    @Test
    public void testAnUnimplementedMajorIsRejected() {
        // Spec §11: a major bump means "a field is renamed, removed, re-nested, retyped, or changes
        // meaning", and the consumer "must refuse the instance". Reading one with the wrong semantics
        // would produce confident, wrong API guidance; rejection degrades to the service index instead,
        // which is a poorer catalog rather than a wrong one.
        Assert.assertEquals(SpecVersionGate.evaluate("v2.0"), SpecVersionGate.VersionVerdict.REJECT);
        Assert.assertFalse(SpecVersionGate.evaluate("v2.0").isUsable());
        Assert.assertEquals(SpecVersionGate.evaluate("v10.3"), SpecVersionGate.VersionVerdict.REJECT);
    }

    @Test
    public void testAMalformedVersionIsRejected() {
        // Not the pre-release form and not v<major>.<minor>, so there is no reading of it this build can
        // claim to implement. "v2" in particular must not be mistaken for the tolerated pre-release form:
        // only "v1" is that.
        Assert.assertEquals(SpecVersionGate.evaluate("1"), SpecVersionGate.VersionVerdict.REJECT);
        Assert.assertEquals(SpecVersionGate.evaluate("V1.0"), SpecVersionGate.VersionVerdict.REJECT);
        Assert.assertEquals(SpecVersionGate.evaluate("v1.0.0"), SpecVersionGate.VersionVerdict.REJECT);
        Assert.assertEquals(SpecVersionGate.evaluate("v2"), SpecVersionGate.VersionVerdict.REJECT);
        Assert.assertEquals(SpecVersionGate.evaluate("v1.x"), SpecVersionGate.VersionVerdict.REJECT);
    }

    @Test
    public void testSurroundingWhitespaceDoesNotChangeTheVerdict() {
        Assert.assertEquals(SpecVersionGate.evaluate(" v1.0 "), SpecVersionGate.VersionVerdict.ACCEPT);
        Assert.assertEquals(SpecVersionGate.evaluate(" v1 "),
                SpecVersionGate.VersionVerdict.ACCEPT_WITH_WARNING);
    }

    @Test
    public void testTheDocumentOverloadReadsTheDeclaredVersion() {
        Assert.assertEquals(
                SpecVersionGate.evaluate(new TriggerMetadataModel("v1.0", null, null, null, null)),
                SpecVersionGate.VersionVerdict.ACCEPT);
        Assert.assertEquals(
                SpecVersionGate.evaluate(new TriggerMetadataModel("v9.0", null, null, null, null)),
                SpecVersionGate.VersionVerdict.REJECT);
        Assert.assertEquals(SpecVersionGate.evaluate((TriggerMetadataModel) null),
                SpecVersionGate.VersionVerdict.ACCEPT_WITH_WARNING);
    }
}
