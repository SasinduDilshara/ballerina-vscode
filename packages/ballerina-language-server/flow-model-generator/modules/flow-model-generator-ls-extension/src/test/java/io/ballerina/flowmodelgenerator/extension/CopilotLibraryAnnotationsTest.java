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

package io.ballerina.flowmodelgenerator.extension;

import io.ballerina.flowmodelgenerator.core.copilot.service.AnnotationLoader;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.Map;

/**
 * Tests for the DB-backed Copilot annotation description loader.
 * Verifies the curated descriptions exposed for FTP and that covered libraries without annotation
 * rows return an empty map.
 *
 * @since 1.7.0
 */
public class CopilotLibraryAnnotationsTest {

    // Disabled: requires service-index regeneration. Re-enable after regeneration.
    @Test(enabled = false)
    public void testFtpAnnotationDescriptions() {
        Map<String, String> descriptions = AnnotationLoader.loadDescriptions("ballerina/ftp");

        Assert.assertNotNull(descriptions.get("ServiceConfig"),
                "Expected a curated description for ftp ServiceConfig, got: " + descriptions);
        Assert.assertNotNull(descriptions.get("FunctionConfig"),
                "Expected a curated description for ftp FunctionConfig, got: " + descriptions);
    }

    @Test
    public void testCoveredLibraryWithoutAnnotationsReturnsEmpty() {
        // kafka is covered but has no rows in the Annotation table
        Map<String, String> descriptions = AnnotationLoader.loadDescriptions("ballerinax/kafka");
        Assert.assertTrue(descriptions.isEmpty(),
                "Expected empty descriptions for kafka (covered, no DB rows)");
    }
}
