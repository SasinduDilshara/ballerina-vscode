/**
 * Copyright (c) 2026, WSO2 LLC. (https://www.wso2.com) All Rights Reserved.
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

// Renders the Copilot library context offline: reads the library JSON written by
// CopilotRenderDumpTest and runs it through the very same `toSyntaxString` the extension uses, so the
// output is byte-for-byte what the LLM receives.
//
//   1. cd packages/ballerina-language-server
//      ./gradlew :flow-model-generator:flow-model-generator-ls-extension:test \
//          --tests '*CopilotRenderDumpTest*' -Dcopilot.render.dump=true
//   2. cd packages/ballerina-extension
//      npx ts-node scripts/render-copilot-view.ts [outputDir]
//
// Writes `<outputDir>/rendered/<library>.bal` plus a `_summary.md` index.

import * as fs from "fs";
import * as path from "path";
import { Library } from "../src/features/ai/utils/libs/library-types";
import { toSyntaxString } from "../src/features/ai/utils/libs/to-syntax-string";

const DEFAULT_OUTPUT_DIR = path.join(
    process.env.HOME ?? "", "Desktop", "Copilot-Changes", "render-trigger-2");

function main(): void {
    const outputDir = process.argv[2] ?? DEFAULT_OUTPUT_DIR;
    const jsonDir = path.join(outputDir, "json");
    if (!fs.existsSync(jsonDir)) {
        console.error(`No library JSON at ${jsonDir}. Run CopilotRenderDumpTest first (see header).`);
        process.exit(1);
    }
    const renderedDir = path.join(outputDir, "rendered");
    fs.mkdirSync(renderedDir, { recursive: true });

    const files = fs.readdirSync(jsonDir).filter((f) => f.endsWith(".json")).sort();
    const summary: string[] = [
        "# What Copilot sees",
        "",
        "Each file is the exact Ballerina-syntax library context handed to the LLM, produced by",
        "`toSyntaxString` from the library JSON the language server emits.",
        "",
        "| library | lines | annotation declarations | annotation attachments | services |",
        "| --- | --- | --- | --- | --- |",
    ];

    for (const file of files) {
        const library: Library = JSON.parse(fs.readFileSync(path.join(jsonDir, file), "utf-8"));
        const rendered = toSyntaxString([library]);
        const target = path.join(renderedDir, file.replace(/\.json$/, ".bal"));
        fs.writeFileSync(target, rendered, "utf-8");

        const lines = rendered.split("\n");
        const declarations = lines.filter((l) => /^public (const )?annotation /.test(l)).length;
        const attachments = lines.filter((l) => /^\s*@/.test(l) && !/^\s*@deprecated\s*$/.test(l)).length;
        summary.push(`| ${library.name} | ${lines.length} | ${declarations} | ${attachments} `
            + `| ${library.services?.length ?? 0} |`);
        console.log(`${library.name}: ${lines.length} lines, ${declarations} annotation declarations, `
            + `${attachments} attachments -> ${target}`);
    }

    fs.writeFileSync(path.join(outputDir, "_summary.md"), summary.join("\n") + "\n", "utf-8");
    console.log(`\nSummary: ${path.join(outputDir, "_summary.md")}`);
}

main();
