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
 * Detects Ballerina runtime panics in the output of a running program.
 *
 * A panic prints as an `error:` line naming the error type and its detail, followed by tab-indented
 * `at` frames, the first of which is where the panic happened:
 *
 *     error: {ballerina}TypeCastError {"message":"incompatible types: 'jwt:Payload' cannot be cast to 'map<json>'"}
 *         at damsith.order_tracker.0:authorizeOrderAccess(main.bal:65)
 *            damsith.order_tracker.0.$anonType$_0:$get$.(main.bal:30)
 *
 * The clients of such a service see only an HTTP 500 (or a dropped connection), and the model reading a
 * page of service output easily passes over the trace. Surfacing the panics as structured data on the
 * run/logs tool results — type, message, and the exact `file.bal:line` — lets the model go straight to
 * the failing statement instead of guessing from the status code.
 *
 * Kept free of `vscode` imports so it can be unit tested in isolation.
 */

export interface RuntimePanic {
    /** The error type as printed, e.g. `{ballerina}TypeCastError` or `{ballerina/lang.array}InvalidUpdate`. */
    errorType: string;
    /** The `message` from the error detail when present, otherwise the raw detail text. */
    message: string;
    /** The innermost frame's `file.bal:line`, e.g. `main.bal:65`, when the trace carries one. */
    location?: string;
    /** The innermost frame's function, e.g. `authorizeOrderAccess`, when the trace carries one. */
    function?: string;
    /** The stack frames as printed, innermost first, without the leading `at`. */
    frames: string[];
}

const PANIC_LINE = /^error:\s+(\{[^}]*\}[A-Za-z_][\w$]*)\s*(.*)$/;
const FRAME_LINE = /^\s+(?:at\s+)?(\S.*)$/;
const FRAME_LOCATION = /([A-Za-z_][\w$.]*)\(([^()]+\.bal:\d+)\)\s*$/;

function extractMessage(detail: string): string {
    const trimmed = detail.trim();
    if (!trimmed) {
        return "";
    }
    try {
        const parsed = JSON.parse(trimmed);
        if (parsed && typeof parsed === "object" && typeof parsed.message === "string") {
            return parsed.message;
        }
    } catch {
        // Not JSON — keep the raw detail.
    }
    return trimmed;
}

/**
 * Every panic trace in the given output, in order of appearance.
 */
export function detectRuntimePanics(output: string): RuntimePanic[] {
    const lines = output.split(/\r?\n/);
    const panics: RuntimePanic[] = [];

    for (let i = 0; i < lines.length; i++) {
        const match = PANIC_LINE.exec(lines[i]);
        if (!match) {
            continue;
        }
        const panic: RuntimePanic = { errorType: match[1], message: extractMessage(match[2]), frames: [] };
        let j = i + 1;
        while (j < lines.length) {
            const frame = FRAME_LINE.exec(lines[j]);
            // Frames are indented; the first un-indented line ends the trace.
            if (!frame || !/^\s/.test(lines[j])) {
                break;
            }
            panic.frames.push(frame[1].trim());
            j++;
        }
        const innermost = panic.frames.find((f) => FRAME_LOCATION.test(f));
        if (innermost) {
            const loc = FRAME_LOCATION.exec(innermost)!;
            const qualified = loc[1];
            panic.function = qualified.substring(qualified.lastIndexOf(":") + 1) || qualified;
            panic.location = loc[2];
        }
        panics.push(panic);
        i = j - 1;
    }
    return panics;
}

/**
 * The note appended to a run/logs tool message when panics were detected. States the consequence the model
 * must not miss: a panic in a resource or remote method is why clients saw a 500 or lost their connection,
 * and where in the source it happened.
 */
export function buildRuntimePanicNote(panics: RuntimePanic[]): string {
    if (panics.length === 0) {
        return "";
    }
    const summary = panics
        .slice(0, 3)
        .map((p) => `${p.errorType}${p.location ? ` at ${p.function ? p.function + " " : ""}(${p.location})` : ""}${p.message ? `: ${p.message}` : ""}`)
        .join("; ");
    return ` ${panics.length} runtime panic(s) detected in the output (see runtimePanics): ${summary}. `
        + `A panic in a resource or remote method is what makes clients receive HTTP 500 or lose their connection `
        + `— fix the statement at the reported location; do not treat the service as working.`;
}
