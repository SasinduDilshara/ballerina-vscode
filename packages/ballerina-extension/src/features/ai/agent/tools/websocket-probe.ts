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

import { tool } from 'ai';
import { z } from 'zod';
import { CopilotEventHandler } from '../../utils/events';
import { BALLERINA_GET_LOGS_TOOL_NAME } from './ballerina-get-logs';
import { BALLERINA_RUN_TOOL_NAME } from './ballerina-run';
import { DEFAULT_WAIT_SECONDS, MAX_WAIT_SECONDS, probeWebSocket, WebSocketProbeResult } from './websocket-probe-core';

export const WEBSOCKET_PROBE_TOOL_NAME = "websocketProbeTool";

const WebSocketProbeInputSchema = z.object({
    url: z.string().describe("The ws:// or wss:// URL of the WebSocket endpoint on this machine, including any query parameters (e.g. ws://localhost:9090/orders/track?orderId=ORD-1001)."),
    headers: z.record(z.string(), z.string()).optional().describe("Extra headers for the upgrade request, e.g. {\"Authorization\": \"Bearer <token>\"}."),
    messages: z.array(z.string()).optional().describe("Text messages to send, in order, after the connection is open. Send JSON as a string."),
    waitSeconds: z.number().min(0).max(MAX_WAIT_SECONDS).optional().describe(`Seconds to keep the connection open collecting frames pushed by the server after the last message. Default ${DEFAULT_WAIT_SECONDS}, max ${MAX_WAIT_SECONDS}. Use a value longer than the service's push interval when it pushes on a timer.`),
    scenario: z.string().max(60).optional().describe("A short description of what this probe verifies, shown to the user."),
});

export type WebSocketProbeInput = z.infer<typeof WebSocketProbeInputSchema>;

export function createWebSocketProbeTool(eventHandler: CopilotEventHandler) {
    return tool({
        description: `Verifies a WebSocket service running on this machine. Performs the HTTP upgrade to the given ws:// or wss:// URL, sends the given text messages in order, and collects every frame the server pushes for waitSeconds.

**Use this — never ${'hurlRunnerTool'} — for anything WebSocket.** Hurl is HTTP-only and cannot open a WebSocket connection.

**Reading the result:**
- \`upgraded: true\` with \`statusCode: 101\` means the handshake succeeded. \`framesReceived\` lists what the server pushed; if the service is expected to push data and this is empty, the service is NOT working as required.
- \`upgraded: false\` means the server refused the upgrade: \`statusCode\` and \`responseBody\` say why (400 = a returned websocket:UpgradeError, 401/403 = authentication, 500 = a panic in the upgrade resource or an incompatible http:Listener). This is a failure to fix, not a pass.
- \`closeCode: 1006\` or \`1011\` means the connection dropped abnormally — check the service logs with ${BALLERINA_GET_LOGS_TOOL_NAME}.
- \`error\` set means the probe could not complete (connection refused, timeout, protocol error). A timeout is never evidence that the service works.

The service must already be running (start it with ${BALLERINA_RUN_TOOL_NAME}). Self-signed certificates on wss:// are accepted because only loopback hosts can be probed.`,
        inputSchema: WebSocketProbeInputSchema,
        execute: async (input: WebSocketProbeInput, context?: { toolCallId?: string }): Promise<WebSocketProbeResult> => {
            const toolCallId = context?.toolCallId || `fallback-${Date.now()}`;
            eventHandler({
                type: "tool_call",
                toolName: WEBSOCKET_PROBE_TOOL_NAME,
                toolInput: { url: input.url, messages: input.messages ?? [], scenario: input.scenario },
                toolCallId,
            });

            const result = await probeWebSocket({
                url: input.url,
                headers: input.headers,
                messages: input.messages,
                waitSeconds: input.waitSeconds,
            });

            console.log(`[WebSocketProbe] ${input.url} → upgraded=${result.upgraded} status=${result.statusCode ?? "-"} `
                + `sent=${result.framesSent} received=${result.framesReceived.length} close=${result.closeCode ?? "-"} error=${result.error ?? "-"}`);

            const failed = !result.upgraded || result.error !== undefined;
            eventHandler({
                type: "tool_result",
                toolName: WEBSOCKET_PROBE_TOOL_NAME,
                toolOutput: { ...result, scenario: input.scenario },
                toolCallId,
                ...(failed ? { failed: true } : {}),
            });
            return result;
        },
    });
}
