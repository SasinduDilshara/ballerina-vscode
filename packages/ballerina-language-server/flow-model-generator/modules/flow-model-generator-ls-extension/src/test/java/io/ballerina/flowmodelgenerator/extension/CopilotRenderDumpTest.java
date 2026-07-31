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
import io.ballerina.flowmodelgenerator.core.copilot.model.Library;
import io.ballerina.flowmodelgenerator.core.copilot.model.ModelToJsonConverter;
import org.testng.SkipException;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Writes the exact library JSON the Copilot pipeline hands to the frontend, one file per library, so
 * the rendered prompt can be produced offline and reviewed by eye.
 *
 * <p>This is a reporting aid, not an assertion: a library that cannot be resolved is skipped and
 * recorded, and the test never fails. The companion script
 * {@code scripts/render-copilot-view.ts} in {@code packages/ballerina-extension} turns each JSON file
 * into the Ballerina-syntax text that actually reaches the LLM.</p>
 *
 * <p>Opt-in, so it never slows the normal suite: run with
 * {@code -Dcopilot.render.dump=true}, and optionally
 * {@code -Dcopilot.render.outputDir=...} to redirect the output.</p>
 *
 * @since 1.7.0
 */
public class CopilotRenderDumpTest {

    private static final String OUTPUT_DIR_PROPERTY = "copilot.render.outputDir";
    /** Inside the module's build directory unless {@value #OUTPUT_DIR_PROPERTY} redirects it. */
    private static final String DEFAULT_OUTPUT_DIR = "build/copilot-render";

    /**
     * Libraries to dump: the three plain libraries whose annotation catalogs the change most affects,
     * followed by every library served from a bundled trigger metadata document.
     */
    private static final List<String> LIBRARIES = List.of(
            "ballerina/http",
            "ballerina/log",
            "ballerina/graphql",
            "ballerinax/kafka",
            "ballerinax/rabbitmq",
            "ballerina/ftp",
            "ballerina/mcp",
            "ballerinax/mssql",
            "ballerinax/trigger.github",
            "ballerina/smb",
            "ballerina/websub",
            "ballerinax/trigger.google.calendar");

    @Test
    public void dumpLibraryJsonForOfflineRendering() throws Exception {
        if (!Boolean.parseBoolean(System.getProperty("copilot.render.dump"))) {
            throw new SkipException("Set -Dcopilot.render.dump=true to write the render inputs");
        }
        Path jsonDir = Path.of(System.getProperty(OUTPUT_DIR_PROPERTY, DEFAULT_OUTPUT_DIR), "json");
        Files.createDirectories(jsonDir);

        GsonBuilder gsonBuilder = new GsonBuilder().setPrettyPrinting();
        StringBuilder report = new StringBuilder();
        for (String libraryName : LIBRARIES) {
            try {
                List<Library> libraries =
                        new CopilotLibraryManager().loadFilteredLibraries(new String[]{libraryName});
                if (libraries.isEmpty()) {
                    report.append(libraryName).append(": UNRESOLVED\n");
                    continue;
                }
                Library library = libraries.get(0);
                Path target = jsonDir.resolve(libraryName.replace('/', '_') + ".json");
                Files.writeString(target,
                        gsonBuilder.create().toJson(ModelToJsonConverter.libraryToJson(library)));
                report.append(libraryName)
                        .append(": annotations=").append(size(library.getAnnotations()))
                        .append(" typeDefs=").append(size(library.getTypeDefs()))
                        .append(" clients=").append(size(library.getClients()))
                        .append(" functions=").append(size(library.getFunctions()))
                        .append(" services=").append(size(library.getServices()))
                        .append('\n');
            } catch (RuntimeException e) {
                report.append(libraryName).append(": FAILED ").append(e).append('\n');
            }
        }
        Files.writeString(jsonDir.resolve("_dump-report.txt"), report.toString());
    }

    private static int size(List<?> list) {
        return list == null ? 0 : list.size();
    }
}
