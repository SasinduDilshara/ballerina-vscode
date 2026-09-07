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
 * System-prompt rules for keeping a panic in one request or message from taking down the whole program.
 *
 * A generated WebSocket `onMessage` let a panic escape; the runtime terminated the process, dropping every
 * other connection (close code 1006) and the HTTP API sharing it. Containment was added only after the user
 * asked for it. The behaviour was reproduced on Ballerina 2201.13.4: a panic escaping a WebSocket remote
 * method or an HTTP resource ends the process (the HTTP caller may still be sent a 500 first), a panic in a
 * WebSocket upgrade `get` resource is answered with HTTP 500 and the process survives, and a returned `error`
 * is logged and the connection stays open. The prompt said nothing about `trap` or panics.
 *
 * Kept in its own module (no imports) so it can be unit-tested without loading the extension host.
 */
export const PANIC_CONTAINMENT_RULES = `## Panic containment in services
- A panic that escapes a service resource or remote method — an HTTP resource, \`onMessage\`/\`onOpen\`/\`onClose\`, or any other listener callback — is NOT contained by the listener: the runtime prints the panic and the WHOLE PROCESS EXITS, taking down every other service, listener and connection in the program. The failing caller may still receive a 500 first, so a contained-looking response is not evidence the server survived. Returning an \`error\` is safe: it is logged and only that request or message fails. Exception: in a WebSocket upgrade \`get\` resource a panic does not kill the process — the listener answers the handshake with HTTP 500 and prints the panic only to stderr, so clients see "Unexpected server response: 500" with nothing in the application log; never let a panic reach the upgrade resource either.
- In any code reachable from a listener callback, prefer error-returning constructs over panicking ones: \`check\` (never \`checkpanic\`); \`x is T\`, \`x.ensureType(T)\` or \`cloneWithType\` instead of the cast \`<T>x\`; a length check before indexing \`a[i]\`; a zero check before \`/\` or \`%\`. This — not \`trap\` — is the primary defence.
- Where a panic is still possible (mutating shared state, helpers you wrote that may panic, e.g. \`push\` on a value that may be \`readonly\`), add ONE \`trap\` at the callback boundary as a last-resort net: \`T|error r = trap doWork(...); if r is error { log:printError("...", 'error = r); return r; }\`. One trap per callback around the risky call — do not scatter \`trap\` over individual statements or wrap calls that already return \`T|error\`.`;
