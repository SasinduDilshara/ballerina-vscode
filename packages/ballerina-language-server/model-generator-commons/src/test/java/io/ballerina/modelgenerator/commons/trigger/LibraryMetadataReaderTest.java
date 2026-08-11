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

import io.ballerina.modelgenerator.commons.ModuleInfo;
import io.ballerina.modelgenerator.commons.trigger.models.TriggerMetadataModel;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Tests {@link LibraryMetadataReader}'s three public reads: {@link LibraryMetadataReader#getTriggerMetadataModel}
 * and {@link LibraryMetadataReader#getTriggerUISchemaModel} (a connector's own shipped
 * {@code trigger-metadata.json}/{@code trigger-ui-schema.json}, resolved from its {@code .bala}) and
 * {@link LibraryMetadataReader#getPackagedTriggerMetadataModel} (the LS's own bundled classpath
 * resource) -- three independent reads, none silently falling back to another. Package/JSON resolution
 * is entirely internal to this class, so these tests only ever go through {@link ModuleInfo}-keyed
 * calls -- never a resolved {@code Path} -- mirroring how a caller (e.g. {@code ConnectorModelReader})
 * is expected to use it.
 */
public class LibraryMetadataReaderTest {

    private static final LibraryMetadataReader READER = LibraryMetadataReader.getInstance();

    @Test
    public void testGetPackagedTriggerMetadataModelHit() {
        // kafka is bundled under trigger-metadata-models/kafka/trigger-metadata.json -- resolved purely
        // off the classpath, no package resolution needed.
        ModuleInfo moduleInfo = new ModuleInfo("ballerinax", "kafka", "kafka", "1.0.0");
        TriggerMetadataModel model = READER.getPackagedTriggerMetadataModel(moduleInfo).orElseThrow();
        Assert.assertFalse(model.listeners().isEmpty());
        Assert.assertFalse(model.serviceTypes().isEmpty());
    }

    @Test
    public void testGetPackagedTriggerMetadataModelMiss() {
        ModuleInfo moduleInfo = new ModuleInfo("ballerinax", "no-such-module", "no-such-module", "1.0.0");
        Assert.assertTrue(READER.getPackagedTriggerMetadataModel(moduleInfo).isEmpty());
    }

    @Test
    public void testGetPackagedTriggerMetadataModelNullModuleInfo() {
        Assert.assertTrue(READER.getPackagedTriggerMetadataModel(null).isEmpty());
    }

    @Test
    public void testGetTriggerMetadataModelNullModuleInfo() {
        Assert.assertTrue(READER.getTriggerMetadataModel(null).isEmpty());
    }

    @Test
    public void testGetTriggerMetadataModelIncompleteModuleInfo() {
        ModuleInfo moduleInfo = new ModuleInfo(null, "kafka", "kafka", "1.0.0");
        Assert.assertTrue(READER.getTriggerMetadataModel(moduleInfo).isEmpty());
    }

    @Test
    public void testGetTriggerUISchemaModelNullModuleInfo() {
        Assert.assertTrue(READER.getTriggerUISchemaModel(null).isEmpty());
    }

    @Test
    public void testGetTriggerUISchemaModelIncompleteModuleInfo() {
        ModuleInfo moduleInfo = new ModuleInfo(null, "kafka", "kafka", "1.0.0");
        Assert.assertTrue(READER.getTriggerUISchemaModel(moduleInfo).isEmpty());
    }

    @Test
    public void testGetTriggerMetadataModelUnresolvableModuleGracefullyEmpty() {
        // Not a real Central package -- must resolve to empty, not throw (the version-less
        // PackageUtil.getModulePackage overload throws on an unknown org/module). Also confirms
        // getTriggerMetadataModel does NOT fall back to the packaged tier: kafka's presence there
        // (see testGetPackagedTriggerMetadataModelHit) must not leak into this connector-owned read.
        ModuleInfo moduleInfo = new ModuleInfo("no-such-org", "no-such-module", "no-such-module", null);
        Assert.assertTrue(READER.getTriggerMetadataModel(moduleInfo).isEmpty());
    }

    @Test
    public void testGetTriggerUISchemaModelUnresolvableModuleGracefullyEmpty() {
        ModuleInfo moduleInfo = new ModuleInfo("no-such-org", "no-such-module", "no-such-module", null);
        Assert.assertTrue(READER.getTriggerUISchemaModel(moduleInfo).isEmpty());
    }

    // ---- the shipped-document path, and why an absence is not a refusal --------------------

    /**
     * The four outcomes of reading a connector-shipped document.
     *
     * <p>This path had no test at all, and could not have had one going in through {@link
     * io.ballerina.projects.Package}: no package published to Central ships a
     * {@code resources/trigger-metadata.json} yet, so there was nothing to read. It is nonetheless the path a
     * future connector takes, and the path on which conflating "no document" with "a document I must not
     * read" causes a caller to serve the LS's own older copy of the same connector.
     */
    @Test
    public void testAPackageShippingNoDocumentReportsAbsent() throws IOException {
        Path root = Files.createTempDirectory("no-metadata");
        LibraryMetadataReader.MetadataRead read = READER.readTriggerMetadataModel(root);
        Assert.assertEquals(read.outcome(), LibraryMetadataReader.MetadataOutcome.ABSENT);
        Assert.assertFalse(read.present(), "nothing was there, so a caller may fill the gap from elsewhere");
        Assert.assertTrue(read.usable().isEmpty());
    }

    @Test
    public void testAShippedDocumentIsReadAndReportedUsable() throws IOException {
        Path root = shipping("""
                {
                  "version": "v1.0",
                  "listeners": [{ "type": { "name": "Listener" }, "services": ["$service"] }],
                  "serviceTypes": [{
                    "id": "$service",
                    "type": { "name": "Service" },
                    "concrete": false,
                    "multipleListenersAllowed": false,
                    "handlers": { "backedByConcreteType": false, "options": [] }
                  }]
                }
                """);
        LibraryMetadataReader.MetadataRead read = READER.readTriggerMetadataModel(root);
        Assert.assertEquals(read.outcome(), LibraryMetadataReader.MetadataOutcome.USABLE);
        Assert.assertTrue(read.present());
        Assert.assertEquals(read.usable().orElseThrow().serviceTypes().size(), 1);
    }

    @Test
    public void testAShippedDocumentOfAnUnimplementedMajorIsRefusedNotAbsent() throws IOException {
        // The regression this guards: a v2 document read as an ABSENCE lets the caller substitute the LS's
        // bundled v1 copy of the SAME connector — answering a v2 package with a v1 contract and presenting it
        // as authoritative. `present()` is what stops that, so it is asserted directly.
        Path root = shipping("""
                { "version": "v2.0", "listeners": [], "serviceTypes": [] }
                """);
        LibraryMetadataReader.MetadataRead read = READER.readTriggerMetadataModel(root);
        Assert.assertEquals(read.outcome(),
                LibraryMetadataReader.MetadataOutcome.UNSUPPORTED_VERSION);
        Assert.assertTrue(read.present(), "the document IS there; it simply may not be read");
        Assert.assertTrue(read.usable().isEmpty());
    }

    @Test
    public void testAMalformedShippedDocumentIsRefusedNotAbsent() throws IOException {
        // A third party with a JSON typo used to get complete silence: empty result, no log line, and a
        // caller that could not tell the file existed.
        Path root = shipping("{ \"version\": \"v1.0\", \"listeners\": [ ");
        LibraryMetadataReader.MetadataRead read = READER.readTriggerMetadataModel(root);
        Assert.assertEquals(read.outcome(), LibraryMetadataReader.MetadataOutcome.MALFORMED);
        Assert.assertTrue(read.present());
        Assert.assertTrue(read.usable().isEmpty());
    }

    @Test
    public void testAShippedDocumentParsingToNothingIsRefusedNotAbsent() throws IOException {
        // Valid JSON, no document. Still a defect in a file that exists, not an absent file.
        Path root = shipping("null");
        LibraryMetadataReader.MetadataRead read = READER.readTriggerMetadataModel(root);
        Assert.assertEquals(read.outcome(), LibraryMetadataReader.MetadataOutcome.MALFORMED);
        Assert.assertTrue(read.present());
    }

    @Test
    public void testTheOptionalReturningReadStillCollapsesEveryFailureToEmpty() throws IOException {
        // The pre-existing API is unchanged for callers that genuinely do not care why.
        Assert.assertTrue(READER.getShippedTriggerMetadataModel(null).isEmpty());
        Assert.assertTrue(READER.readTriggerMetadataModel(shipping("{ \"version\": \"v9.0\" }"))
                .usable().isEmpty());
    }

    @Test
    public void testAFailureBeforeTheDocumentIsEvenReadReportsAbsentNotMalformed() throws IOException {
        // The distinction is expensive, not cosmetic. MALFORMED makes `present()` true, which tells the
        // caller to suppress BOTH the LS-bundled document AND the service index — so classifying an
        // unrelated failure as MALFORMED costs the library its entire service catalog, where before this
        // tri-state existed it cost nothing. Anything that fails before the document's content is in
        // question ("we could not even look") is therefore ABSENT.
        //
        // Provoked with a path that is a FILE where a package root must be a directory, so resolving
        // `resources/trigger-metadata.json` under it cannot describe a document either way.
        Path notADirectory = Files.createTempFile("not-a-package", ".txt");
        LibraryMetadataReader.MetadataRead read = READER.readTriggerMetadataModel(notADirectory);
        Assert.assertEquals(read.outcome(), LibraryMetadataReader.MetadataOutcome.ABSENT);
        Assert.assertFalse(read.present(),
                "a package we could not inspect must not be reported as shipping a broken document");
    }

    @Test
    public void testANullPackageReportsAbsent() {
        LibraryMetadataReader.MetadataRead read = READER.readShippedTriggerMetadata(null);
        Assert.assertEquals(read.outcome(), LibraryMetadataReader.MetadataOutcome.ABSENT);
        Assert.assertFalse(read.present());
    }

    /** A package root shipping the given {@code resources/trigger-metadata.json}. */
    private static Path shipping(String json) throws IOException {
        Path root = Files.createTempDirectory("shipped-metadata");
        Path resources = Files.createDirectories(root.resolve("resources"));
        Files.writeString(resources.resolve("trigger-metadata.json"), json, StandardCharsets.UTF_8);
        return root;
    }
}
