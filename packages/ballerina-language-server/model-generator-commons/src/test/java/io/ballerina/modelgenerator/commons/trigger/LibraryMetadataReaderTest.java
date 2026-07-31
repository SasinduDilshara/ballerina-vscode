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
 *
 * <p>{@link LibraryMetadataReader#resolveTriggerMetadataModel} is the composed read: the connector's
 * own document first, the bundled copy second.</p>
 */
public class LibraryMetadataReaderTest {

    private static final LibraryMetadataReader READER = LibraryMetadataReader.getInstance();

    // ---- resolveTriggerMetadataModel: connector's own document first, bundled second -------------

    /**
     * With a package root that ships no {@code resources/trigger-metadata.json}, resolution falls
     * through to the bundled copy. The scratch directory stands in for any such package.
     */
    @Test
    public void testResolveFallsBackToTheBundledCopy() throws Exception {
        Path emptyRoot = Files.createTempDirectory("no-trigger-metadata");
        ModuleInfo moduleInfo = new ModuleInfo("ballerinax", "kafka", "kafka", null);
        TriggerMetadataModel model =
                READER.resolveTriggerMetadataModel(emptyRoot, moduleInfo).orElseThrow();
        Assert.assertFalse(model.serviceTypes().isEmpty(), "Expected the bundled kafka document");
    }

    /**
     * A connector that ships its own document wins over the bundled copy of the same module, so a
     * connector describing itself is served without waiting for an LS release.
     */
    @Test
    public void testConnectorsOwnDocumentWinsOverTheBundledCopy() throws Exception {
        Path root = Files.createTempDirectory("own-trigger-metadata");
        Path resources = Files.createDirectories(root.resolve("resources"));
        // A minimal document that is unmistakably not the bundled kafka one.
        Files.writeString(resources.resolve("trigger-metadata.json"), """
                {
                  "listeners": [{"type": {"name": "OwnListener"}}],
                  "serviceTypes": [{"id": "own", "type": {"name": "OwnService"}}],
                  "annotations": []
                }
                """);

        ModuleInfo moduleInfo = new ModuleInfo("ballerinax", "kafka", "kafka", null);
        TriggerMetadataModel model = READER.resolveTriggerMetadataModel(root, moduleInfo).orElseThrow();
        Assert.assertEquals(model.serviceTypes().get(0).type().name(), "OwnService",
                "The connector's own document must take precedence over the bundled kafka one");
        Assert.assertEquals(model.listeners().get(0).type().name(), "OwnListener");

        // The bundled read on its own is unaffected -- the tiers stay independent.
        Assert.assertNotEquals(READER.getPackagedTriggerMetadataModel(moduleInfo).orElseThrow()
                .serviceTypes().get(0).type().name(), "OwnService");
    }

    /** A null package root means there is nothing of the connector's to read; the bundled tier serves. */
    @Test
    public void testResolveWithNoPackageRootUsesTheBundledCopyOnly() {
        ModuleInfo moduleInfo = new ModuleInfo("ballerinax", "kafka", "kafka", null);
        Assert.assertTrue(READER.resolveTriggerMetadataModel(null, moduleInfo).isPresent());
        // Neither tier has a document for a module nobody ships or bundles.
        Assert.assertTrue(READER.resolveTriggerMetadataModel(
                null, new ModuleInfo("ballerinax", "nope", "nope", null)).isEmpty());
    }

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
}
