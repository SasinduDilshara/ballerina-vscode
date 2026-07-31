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

import com.google.gson.GsonBuilder;
import io.ballerina.flowmodelgenerator.core.copilot.CopilotLibraryManager;
import io.ballerina.flowmodelgenerator.core.copilot.model.Annotation;
import io.ballerina.flowmodelgenerator.core.copilot.model.AnnotationAttachment;
import io.ballerina.flowmodelgenerator.core.copilot.model.Client;
import io.ballerina.flowmodelgenerator.core.copilot.model.Field;
import io.ballerina.flowmodelgenerator.core.copilot.model.Library;
import io.ballerina.flowmodelgenerator.core.copilot.model.LibraryFunction;
import io.ballerina.flowmodelgenerator.core.copilot.model.ModelToJsonConverter;
import io.ballerina.flowmodelgenerator.core.copilot.model.Parameter;
import io.ballerina.flowmodelgenerator.core.copilot.model.TypeDef;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Verifies the annotation data now delivered to Copilot from the Semantic Model:
 *   (A) the annotation DEFINITION catalog now covers non-service attach points, and
 *   (B) per-symbol annotation ATTACHMENTS surface on functions/params/fields/clients/types.
 *
 * <p>These tests resolve real packages from Ballerina Central (like the other Copilot tests),
 * so they require the packages to be resolvable (local bala cache or network).</p>
 */
public class CopilotAnnotationTest {

    private static final Logger LOGGER = Logger.getLogger(CopilotAnnotationTest.class.getName());

    private static final Set<String> SERVICE_POINTS = Set.of("SERVICE", "OBJECT_METHOD");

    /**
     * (A) Definition catalog — ballerina/http declares many annotations on non-service points
     * (e.g. Payload on PARAMETER, Header on PARAMETER, CallerInfo on PARAMETER, Query, etc.).
     * Before this change the catalog only ever contained SERVICE / OBJECT_METHOD entries.
     */
    @Test
    public void testHttpAnnotationCatalogHasNonServicePoints() {
        Library http = loadOne("ballerina/http");

        List<Annotation> annotations = http.getAnnotations();
        Assert.assertNotNull(annotations, "http should expose an annotation catalog");

        LOGGER.info("===== ballerina/http annotation CATALOG (name -> attachmentPoint) =====");
        annotations.forEach(a -> LOGGER.info("  @" + a.getName() + "  on  " + a.getAttachmentPoint()
                + (a.getTypeConstraint() != null ? "   [constraint: " + a.getTypeConstraint().getName() + "]" : "")
                + (a.getDescription() != null ? "   [desc: " + a.getDescription() + "]" : "")));

        long nonServicePoints = annotations.stream()
                .filter(a -> !SERVICE_POINTS.contains(a.getAttachmentPoint()))
                .count();
        Assert.assertTrue(nonServicePoints > 0,
                "Expected http catalog to now include non-service attach points (FUNCTION/PARAMETER/etc.)");
    }

    /**
     * (B) Per-symbol attachments — connector APIs commonly carry @display on the client / params.
     * Logs every attachment found so you can eyeball exactly what Copilot receives.
     */
    @Test
    public void testConnectorPerSymbolAttachments() {
        Library lib = loadOne("ballerinax/salesforce");

        int[] count = {0};
        if (lib.getClients() != null) {
            for (Client client : lib.getClients()) {
                dumpAttachments("client " + client.getName(), client.getAnnotations(), count);
                if (client.getFunctions() != null) {
                    for (LibraryFunction fn : client.getFunctions()) {
                        dumpAttachments("  fn " + fn.getName(), fn.getAnnotations(), count);
                        if (fn.getParameters() != null) {
                            for (Parameter p : fn.getParameters()) {
                                dumpAttachments("    param " + p.getName(), p.getAnnotations(), count);
                            }
                        }
                    }
                }
            }
        }
        if (lib.getTypeDefs() != null) {
            for (TypeDef td : lib.getTypeDefs()) {
                dumpAttachments("type " + td.getName(), td.getAnnotations(), count);
                if (td.getFields() != null) {
                    for (Field f : td.getFields()) {
                        dumpAttachments("  field " + f.getName(), f.getAnnotations(), count);
                    }
                }
            }
        }
        LOGGER.info("===== total per-symbol attachments found on ballerinax/salesforce: " + count[0]);
        // Soft assertion: connectors are generated with @display, so we expect at least one.
        Assert.assertTrue(count[0] > 0,
                "Expected at least one per-symbol annotation attachment on the salesforce connector");
    }

    /**
     * Logs the FULL Copilot JSON for a library so you can see end-to-end exactly what is sent.
     * Never fails — it is a visibility aid. Change the library name to inspect any package.
     */
    @Test
    public void dumpCopilotJsonForHttp() {
        Library http = loadOne("ballerina/http");
        String json = new GsonBuilder().setPrettyPrinting().create()
                .toJson(ModelToJsonConverter.libraryToJson(http));
        LOGGER.info("===== FULL Copilot JSON for ballerina/http =====\n" + json);
    }

    private static void dumpAttachments(String owner, List<AnnotationAttachment> annotations, int[] count) {
        if (annotations == null || annotations.isEmpty()) {
            return;
        }
        count[0] += annotations.size();
        StringBuilder sb = new StringBuilder();
        for (AnnotationAttachment a : annotations) {
            String prefix = a.getModule() != null ? a.getModule() + ":" : "";
            sb.append(" @").append(prefix).append(a.getName());
            if (a.getValue() != null) {
                sb.append(" ").append(a.getValue());
            }
        }
        LOGGER.info(owner + "  ->  " + sb);
    }

    // ---- catalog completeness & consistency ------------------------------------------------

    /**
     * The catalog must carry every attachment point the compiler reports, including the SERVICE and
     * OBJECT_METHOD points the retired service-index used to own. {@code ballerina/http} declares
     * {@code ServiceConfig on service, type}, {@code ResourceConfig on object function},
     * {@code Payload on parameter, return}, {@code CallerInfo on parameter},
     * {@code Header on parameter, record field}, {@code Query on parameter, record field} and
     * {@code Cache on return} — nine (name, point) pairs against the two the index held.
     */
    @Test
    public void testHttpCatalogCoversEveryDeclaredPoint() {
        Library http = loadOne("ballerina/http");
        Set<String> pairs = pairs(http);

        for (String expected : List.of(
                "ServiceConfig::SERVICE", "ServiceConfig::TYPE",
                "ResourceConfig::OBJECT_METHOD",
                "Payload::PARAMETER", "Payload::RETURN",
                "CallerInfo::PARAMETER",
                "Header::PARAMETER", "Header::RECORD_FIELD",
                "Query::PARAMETER", "Query::RECORD_FIELD",
                "Cache::RETURN")) {
            Assert.assertTrue(pairs.contains(expected),
                    "Missing " + expected + " from the http catalog: " + pairs);
        }
    }

    /**
     * The regression this whole change turns on: an annotation must never appear twice under
     * conflicting attachment points. That is exactly what a naive merge of an index-sourced catalog
     * with a Semantic-Model one produced (ftp's FunctionConfig as both OBJECT_METHOD and RESOURCE).
     */
    @Test
    public void testNoAnnotationIsReportedUnderConflictingPoints() {
        for (String library : List.of("ballerina/ftp", "ballerina/smb", "ballerina/mcp",
                "ballerina/http", "ballerina/graphql", "ballerina/log")) {
            Library lib = loadOne(library);
            Set<String> seen = new HashSet<>();
            for (Annotation annotation : lib.getAnnotations()) {
                String key = annotation.getName() + "::" + annotation.getAttachmentPoint();
                Assert.assertTrue(seen.add(key),
                        library + " reports " + key + " more than once");
            }
        }
    }

    /**
     * Annotations the curated index never held, now delivered. Each was silently absent before:
     * {@code mcp}'s {@code Tool} (the index had only mcp's ServiceConfig),
     * {@code graphql}'s {@code InterceptorConfig} ({@code on class}), and {@code log}'s
     * {@code Sensitive} ({@code on record field}, and log had no index rows at all).
     */
    @Test
    public void testAnnotationsTheIndexNeverHeldAreNowDelivered() {
        Assert.assertTrue(pairs(loadOne("ballerina/mcp")).contains("Tool::OBJECT_METHOD"),
                "mcp's @Tool must reach the catalog");
        Assert.assertTrue(pairs(loadOne("ballerina/graphql")).contains("InterceptorConfig::CLASS"),
                "graphql's @InterceptorConfig must reach the catalog");
        Assert.assertTrue(pairs(loadOne("ballerina/log")).contains("Sensitive::RECORD_FIELD"),
                "log's @Sensitive must reach the catalog");
    }

    /**
     * The attachment point reported must be the one the compiler resolved, never a remapped one.
     * {@code ftp} declares {@code FunctionConfig on service remote function}, which the compiler
     * reports as RESOURCE; {@code smb} declares plain {@code on function}, reported as FUNCTION.
     */
    @Test
    public void testAttachmentPointsAreReportedAsDeclared() {
        Assert.assertTrue(pairs(loadOne("ballerina/ftp")).contains("FunctionConfig::RESOURCE"),
                "ftp's `on service remote function` must be reported as RESOURCE, not OBJECT_METHOD");
        Assert.assertTrue(pairs(loadOne("ballerina/smb")).contains("FunctionConfig::FUNCTION"),
                "smb's `on function` must be reported as FUNCTION, not OBJECT_METHOD");
    }

    /** Every catalog entry must carry a name and a point, and no entry may carry a stale key. */
    @Test
    public void testCatalogEntriesAreWellFormed() {
        Library http = loadOne("ballerina/http");
        for (Annotation annotation : http.getAnnotations()) {
            Assert.assertNotNull(annotation.getName());
            Assert.assertFalse(annotation.getName().isEmpty());
            Assert.assertNotNull(annotation.getAttachmentPoint(), "on @" + annotation.getName());
            Assert.assertFalse(annotation.getAttachmentPoint().isEmpty());
        }
    }

    private static Set<String> pairs(Library library) {
        Assert.assertNotNull(library.getAnnotations(), library.getName() + " has no catalog");
        Set<String> pairs = new HashSet<>();
        for (Annotation annotation : library.getAnnotations()) {
            pairs.add(annotation.getName() + "::" + annotation.getAttachmentPoint());
        }
        return pairs;
    }

    private static Library loadOne(String name) {
        List<Library> libs = new CopilotLibraryManager().loadFilteredLibraries(new String[]{name});
        Assert.assertFalse(libs.isEmpty(), "Expected " + name + " to resolve");
        return libs.get(0);
    }
}
