// Copyright (c) 2026, WSO2 LLC. (https://www.wso2.com/) All Rights Reserved.

// WSO2 LLC. licenses this file to you under the Apache License,
// Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.
// You may obtain a copy of the License at

// http://www.apache.org/licenses/LICENSE-2.0

// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.

/**
 * Dev harness, the second half of the render comparison. Reads the wire payloads written by the Java
 * `dumpCopilotRender` task and renders each one through `toSyntaxString` — the exact function
 * `LibraryGetTool` calls before handing the text to the model — so the committed `.txt` is the prompt
 * text the Copilot would actually receive for that library.
 *
 * The LLM relevance filter that normally sits between the two is deliberately absent. It is a
 * non-deterministic Haiku call, so including it would make two runs differ for reasons that have nothing
 * to do with the change under test. Skipping it renders the library's *whole* surface rather than a
 * query-specific slice, which is a superset: anything the filter could have selected is present here.
 *
 * Usage:
 *   npx ts-node --transpile-only scripts/render-copilot-libraries.ts <dir>
 * where <dir> contains a `json/` subdirectory of dumps and receives the `.txt` renders.
 */

import * as fs from "fs";
import * as path from "path";
import { Library } from "../src/features/ai/utils/libs/library-types";
import { toSyntaxString } from "../src/features/ai/utils/libs/to-syntax-string";

/** Files the Java dumper writes alongside the payloads, which are not libraries. */
const NON_PAYLOAD = new Set(["_versions.json", "_dump-report.txt"]);

function main(): void {
    const root = process.argv[2];
    if (!root) {
        console.error("usage: render-copilot-libraries.ts <dir containing json/>");
        process.exit(2);
    }
    const jsonDir = path.join(root, "json");
    if (!fs.existsSync(jsonDir)) {
        console.error(`no json/ directory under ${root} — run the Java dumpCopilotRender task first`);
        process.exit(2);
    }

    const inputs = fs
        .readdirSync(jsonDir)
        .filter((f) => f.endsWith(".json") && !NON_PAYLOAD.has(f))
        .sort();

    const summary: string[] = [];
    for (const file of inputs) {
        const name = file.replace(/\.json$/, "");
        const raw = fs.readFileSync(path.join(jsonDir, file), "utf-8");
        const libraries = JSON.parse(raw) as Library[];
        // Rendered one library per file even though `toSyntaxString` accepts a list: a per-library file is
        // what makes the before/after comparison attributable to a library rather than to a position in a
        // concatenated blob.
        const rendered = toSyntaxString(libraries);
        const outFile = path.join(root, `${name}.txt`);
        fs.writeFileSync(outFile, rendered, "utf-8");
        summary.push(
            `${name}: ${rendered.split("\n").length} lines, ${rendered.length} chars` +
                (libraries.length === 0 ? "  (EMPTY PAYLOAD)" : "")
        );
    }

    const report = [`rendered ${inputs.length} libraries from ${jsonDir}`, ...summary].join("\n");
    fs.writeFileSync(path.join(root, "_render-report.txt"), report + "\n", "utf-8");
    console.log(report);
}

main();
