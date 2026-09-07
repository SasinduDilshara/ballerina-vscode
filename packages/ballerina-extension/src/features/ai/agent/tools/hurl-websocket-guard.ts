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
 * Detects a Hurl script that tries to talk to a WebSocket endpoint.
 *
 * Hurl drives curl, which cannot complete a WebSocket session: such a request either errors or sits until
 * the 30-second timeout, and both come back as an entry with `status: "error"`. In testing, the agent read
 * those timeouts as a successful upgrade and declared a WebSocket service verified without a single frame
 * having been received. Rejecting the script up front, with a message that names the failure and the right
 * tool, removes the ambiguity.
 *
 * Only the two places a WebSocket intent can be expressed are inspected — the request line's URL scheme
 * and the request's header block — so a `ws://` inside a JSON body, a raw triple-backtick body, or an
 * assertion such as `header "Upgrade" == "websocket"` does not trip it.
 *
 * Kept free of `vscode` imports so it can be unit tested in isolation.
 */

const HURL_METHODS = new Set([
    "GET", "HEAD", "POST", "PUT", "DELETE", "CONNECT", "OPTIONS", "TRACE", "PATCH",
    "LINK", "UNLINK", "PURGE", "LOCK", "UNLOCK", "PROPFIND", "VIEW",
]);

const SECTION_HEADER = /^\[[A-Za-z]+\]$/;

export interface WebSocketUsage {
    /** 1-based line of the script where the WebSocket intent was found. */
    line: number;
    /** What was matched: a `ws://`/`wss://` request URL or a WebSocket upgrade header. */
    reason: string;
}

/**
 * The first WebSocket intent in the script, or `null` when the script is plain HTTP.
 */
export function detectWebSocketUsage(hurlScript: string): WebSocketUsage | null {
    const lines = hurlScript.split(/\r?\n/);
    let inRawBody = false;
    let inHeaders = false;

    for (let i = 0; i < lines.length; i++) {
        const raw = lines[i];
        const line = raw.trim();

        if (line.startsWith("```")) {
            inRawBody = !inRawBody;
            inHeaders = false;
            continue;
        }
        if (inRawBody) {
            continue;
        }

        const tokens = line.split(/\s+/);
        if (tokens.length >= 2 && HURL_METHODS.has(tokens[0])) {
            inHeaders = true;
            if (/^wss?:\/\//i.test(tokens[1])) {
                return { line: i + 1, reason: `request URL '${tokens[1]}' uses the ${tokens[1].slice(0, tokens[1].indexOf(":")).toLowerCase()} scheme` };
            }
            continue;
        }

        // A blank line ends the header block (a body or the next request follows); so does a section such as
        // [Asserts] or [Options], and so does the response spec `HTTP 200`.
        if (line.length === 0 || SECTION_HEADER.test(line) || /^HTTP(\/[0-9.]+)?\s+\d{3}/.test(line)) {
            inHeaders = false;
            continue;
        }

        if (inHeaders) {
            if (/^upgrade\s*:\s*websocket\b/i.test(line)) {
                return { line: i + 1, reason: "the request carries an 'Upgrade: websocket' header" };
            }
            if (/^sec-websocket-key\s*:/i.test(line)) {
                return { line: i + 1, reason: "the request carries a 'Sec-WebSocket-Key' header" };
            }
        }
    }
    return null;
}

/**
 * The warning shown to the model and the user when a WebSocket script is rejected. Names the tool to use
 * instead and states explicitly how a Hurl timeout must NOT be read.
 */
export function websocketRejectionMessage(usage: WebSocketUsage, probeToolName: string): string {
    return `Hurl is HTTP-only and cannot open WebSocket connections (line ${usage.line}: ${usage.reason}). `
        + `The script was not executed. A Hurl timeout or a missing HTTP 101 against a WebSocket endpoint is a failure, `
        + `never evidence that the upgrade worked. Use the ${probeToolName} tool to verify a WebSocket service: it performs `
        + `the upgrade, reports the HTTP status when the upgrade is refused, sends messages and returns the frames the server pushes.`;
}
