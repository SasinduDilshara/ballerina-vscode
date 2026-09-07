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
 * A WebSocket probe for verifying a locally running service: performs the opening handshake over `http`
 * or `https`, reports the HTTP status and body when the upgrade is refused, sends the given text messages
 * in order, and collects every frame the server pushes until the wait window closes or the server closes.
 *
 * Built on the raw `http` module deliberately. What the agent needs from a failed upgrade is the status
 * (400 for a returned `websocket:UpgradeError`, 401/403 from auth, 500 from a panic in the upgrade
 * resource) — Node's own WebSocket client reports only "non-101 status code" and is behind an experimental
 * flag in the extension host's runtime. Every path resolves: a probe never rejects, so the tool can always
 * hand the model a structured result.
 *
 * Restricted to loopback hosts: the probe exists to exercise a service the agent just started, and an
 * unrestricted outbound socket from the extension host would be a new egress path.
 */

import * as http from "http";
import * as https from "https";
import * as net from "net";
import {
    computeAcceptKey,
    createHandshakeKey,
    decodeClosePayload,
    decodeFrames,
    encodeCloseFrame,
    encodeFrame,
    encodeTextFrame,
    OPCODE_BINARY,
    OPCODE_CLOSE,
    OPCODE_CONTINUATION,
    OPCODE_PING,
    OPCODE_PONG,
    OPCODE_TEXT,
} from "./websocket-frames";

export const DEFAULT_WAIT_SECONDS = 5;
export const MAX_WAIT_SECONDS = 30;
/** Time allowed for the TCP connect plus the handshake response before the probe gives up. */
export const DEFAULT_CONNECT_TIMEOUT_MS = 10_000;
/** How much of a refused upgrade's body is kept — enough for an error payload, not a whole page. */
const MAX_RESPONSE_BODY_BYTES = 4096;
/** How many frames are kept; a chatty service is still reported, just not verbatim. */
const MAX_FRAMES = 200;

const LOOPBACK_HOSTS = new Set(["localhost", "127.0.0.1", "::1", "[::1]", "0.0.0.0"]);

export interface WebSocketProbeOptions {
    /** `ws://` or `wss://` URL of the endpoint, including any query parameters. */
    url: string;
    /** Extra request headers for the upgrade request, e.g. an `Authorization` header. */
    headers?: Record<string, string>;
    /** Text messages to send, in order, once the connection is open. */
    messages?: string[];
    /** How long to keep the connection open collecting frames after the last message. */
    waitSeconds?: number;
    /** Overridable for tests; production callers use the default. */
    connectTimeoutMs?: number;
}

export interface ReceivedFrame {
    type: "text" | "binary";
    /** UTF-8 text for text frames; base64 for binary frames. */
    data: string;
}

export interface WebSocketProbeResult {
    url: string;
    /** True only when the server answered the handshake with HTTP 101 and a valid accept key. */
    upgraded: boolean;
    /** The HTTP status of the handshake response — 101 on success, the refusal status otherwise. */
    statusCode?: number;
    statusMessage?: string;
    /** Present when the upgrade was refused: what the server answered instead. */
    responseHeaders?: Record<string, string>;
    responseBody?: string;
    framesSent: number;
    framesReceived: ReceivedFrame[];
    /** Set when the server closed the connection; absent when the probe closed it after the wait window. */
    closeCode?: number;
    closeReason?: string;
    /** A transport or protocol error: connection refused, timeout, bad accept key, ... */
    error?: string;
    timedOut: boolean;
    durationMs: number;
}

/** Whether the URL points at a loopback address — the only hosts the probe will talk to. */
export function isLoopbackUrl(url: string): boolean {
    let parsed: URL;
    try {
        parsed = new URL(url);
    } catch {
        return false;
    }
    const host = parsed.hostname.toLowerCase();
    if (LOOPBACK_HOSTS.has(host)) {
        return true;
    }
    // 127.0.0.0/8, and IPv6 loopback written without brackets after URL parsing.
    if (net.isIPv4(host)) {
        return host.startsWith("127.");
    }
    return host === "::1";
}

function toHttpUrl(url: string): URL {
    const parsed = new URL(url);
    if (parsed.protocol === "ws:") {
        parsed.protocol = "http:";
    } else if (parsed.protocol === "wss:") {
        parsed.protocol = "https:";
    } else if (parsed.protocol !== "http:" && parsed.protocol !== "https:") {
        throw new Error(`Unsupported URL scheme '${parsed.protocol}' — use ws:// or wss://`);
    }
    return parsed;
}

function headersToRecord(headers: http.IncomingHttpHeaders): Record<string, string> {
    const record: Record<string, string> = {};
    for (const [name, value] of Object.entries(headers)) {
        if (value === undefined) {
            continue;
        }
        record[name] = Array.isArray(value) ? value.join(", ") : value;
    }
    return record;
}

/**
 * Runs the probe. Never rejects: every failure mode is reported in the result.
 */
export function probeWebSocket(options: WebSocketProbeOptions): Promise<WebSocketProbeResult> {
    const startedAt = Date.now();
    const waitSeconds = Math.min(Math.max(options.waitSeconds ?? DEFAULT_WAIT_SECONDS, 0), MAX_WAIT_SECONDS);
    const connectTimeoutMs = options.connectTimeoutMs ?? DEFAULT_CONNECT_TIMEOUT_MS;
    const messages = options.messages ?? [];

    const result: WebSocketProbeResult = {
        url: options.url,
        upgraded: false,
        framesSent: 0,
        framesReceived: [],
        timedOut: false,
        durationMs: 0,
    };

    return new Promise<WebSocketProbeResult>((resolve) => {
        let settled = false;
        let socket: net.Socket | undefined;
        let connectTimer: NodeJS.Timeout | undefined;
        let waitTimer: NodeJS.Timeout | undefined;

        const finish = (): void => {
            if (settled) {
                return;
            }
            settled = true;
            if (connectTimer) {
                clearTimeout(connectTimer);
            }
            if (waitTimer) {
                clearTimeout(waitTimer);
            }
            if (socket && !socket.destroyed) {
                socket.destroy();
            }
            result.durationMs = Date.now() - startedAt;
            resolve(result);
        };

        const fail = (message: string): void => {
            if (!settled && !result.error) {
                result.error = message;
            }
            finish();
        };

        if (!isLoopbackUrl(options.url)) {
            fail("The WebSocket probe only connects to services on this machine (localhost / 127.0.0.1 / ::1).");
            return;
        }

        let target: URL;
        try {
            target = toHttpUrl(options.url);
        } catch (e) {
            fail(e instanceof Error ? e.message : String(e));
            return;
        }

        const handshakeKey = createHandshakeKey();
        const expectedAccept = computeAcceptKey(handshakeKey);
        const requestHeaders: Record<string, string> = {
            ...(options.headers ?? {}),
            Connection: "Upgrade",
            Upgrade: "websocket",
            "Sec-WebSocket-Version": "13",
            "Sec-WebSocket-Key": handshakeKey,
        };

        const isTls = target.protocol === "https:";
        const requestOptions: https.RequestOptions = {
            method: "GET",
            host: target.hostname.replace(/^\[|\]$/g, ""),
            port: target.port ? Number(target.port) : (isTls ? 443 : 80),
            path: `${target.pathname}${target.search}`,
            headers: requestHeaders,
            // Local services under test routinely run on self-signed certificates; the probe only ever
            // talks to loopback, so skipping verification cannot expose it to a third party.
            ...(isTls ? { rejectUnauthorized: false } : {}),
        };

        const req = (isTls ? https : http).request(requestOptions);

        connectTimer = setTimeout(() => {
            result.timedOut = true;
            req.destroy();
            fail(`No handshake response within ${connectTimeoutMs} ms — the service is not listening, or it never answered the upgrade request.`);
        }, connectTimeoutMs);

        req.on("error", (err: NodeJS.ErrnoException) => {
            if (err.code === "ECONNREFUSED") {
                fail(`Connection refused at ${target.host} — nothing is listening on that port.`);
                return;
            }
            fail(`${err.code ? err.code + ": " : ""}${err.message}`);
        });

        // A non-101 answer: the upgrade was refused. Report exactly what the server said.
        req.on("response", (res) => {
            if (connectTimer) {
                clearTimeout(connectTimer);
            }
            result.statusCode = res.statusCode;
            result.statusMessage = res.statusMessage;
            result.responseHeaders = headersToRecord(res.headers);
            const chunks: Buffer[] = [];
            let received = 0;
            res.on("data", (chunk: Buffer) => {
                if (received < MAX_RESPONSE_BODY_BYTES) {
                    chunks.push(chunk.subarray(0, MAX_RESPONSE_BODY_BYTES - received));
                    received += chunk.length;
                }
            });
            const done = (): void => {
                result.responseBody = Buffer.concat(chunks).toString("utf8");
                result.error = `The server refused the WebSocket upgrade with HTTP ${res.statusCode}${res.statusMessage ? " " + res.statusMessage : ""}.`;
                finish();
            };
            res.on("end", done);
            res.on("error", done);
        });

        req.on("upgrade", (res, upgradedSocket, head) => {
            if (connectTimer) {
                clearTimeout(connectTimer);
            }
            socket = upgradedSocket;
            result.statusCode = res.statusCode;
            result.statusMessage = res.statusMessage;
            result.responseHeaders = headersToRecord(res.headers);

            const accept = res.headers["sec-websocket-accept"];
            if (accept !== expectedAccept) {
                fail(`The server answered HTTP 101 but its Sec-WebSocket-Accept header does not match the key sent (got '${accept ?? "none"}').`);
                return;
            }
            result.upgraded = true;

            let pending: Buffer = Buffer.from(head);
            let fragments: { opcode: number; parts: Buffer[] } | undefined;

            const recordFrame = (opcode: number, payload: Buffer): void => {
                if (result.framesReceived.length >= MAX_FRAMES) {
                    return;
                }
                if (opcode === OPCODE_TEXT) {
                    result.framesReceived.push({ type: "text", data: payload.toString("utf8") });
                } else {
                    result.framesReceived.push({ type: "binary", data: payload.toString("base64") });
                }
            };

            const consume = (chunk: Buffer): void => {
                pending = Buffer.concat([pending, chunk]);
                let decoded;
                try {
                    decoded = decodeFrames(pending);
                } catch (e) {
                    fail(e instanceof Error ? e.message : String(e));
                    return;
                }
                pending = decoded.rest;
                for (const frame of decoded.frames) {
                    switch (frame.opcode) {
                        case OPCODE_TEXT:
                        case OPCODE_BINARY:
                            if (frame.fin) {
                                recordFrame(frame.opcode, frame.payload);
                            } else {
                                fragments = { opcode: frame.opcode, parts: [frame.payload] };
                            }
                            break;
                        case OPCODE_CONTINUATION:
                            if (fragments) {
                                fragments.parts.push(frame.payload);
                                if (frame.fin) {
                                    recordFrame(fragments.opcode, Buffer.concat(fragments.parts));
                                    fragments = undefined;
                                }
                            }
                            break;
                        case OPCODE_PING:
                            if (!socket?.destroyed) {
                                socket?.write(encodeFrame(OPCODE_PONG, frame.payload));
                            }
                            break;
                        case OPCODE_PONG:
                            break;
                        case OPCODE_CLOSE: {
                            const close = decodeClosePayload(frame.payload);
                            result.closeCode = close.code;
                            result.closeReason = close.reason;
                            if (!socket?.destroyed) {
                                socket?.write(encodeCloseFrame(close.code === 1005 ? 1000 : close.code));
                            }
                            finish();
                            return;
                        }
                        default:
                            break;
                    }
                }
            };

            upgradedSocket.on("data", consume);
            upgradedSocket.on("error", (err: Error) => fail(err.message));
            upgradedSocket.on("close", () => {
                // Closed without a close frame (e.g. the process died): report it as an abnormal closure.
                if (!settled && result.closeCode === undefined) {
                    result.closeCode = 1006;
                    result.closeReason = "connection closed without a close frame";
                }
                finish();
            });

            if (head.length > 0) {
                pending = Buffer.alloc(0);
                consume(Buffer.from(head));
            }

            for (const message of messages) {
                if (settled) {
                    break;
                }
                upgradedSocket.write(encodeTextFrame(message));
                result.framesSent++;
            }

            waitTimer = setTimeout(() => {
                if (!settled && !upgradedSocket.destroyed) {
                    upgradedSocket.write(encodeCloseFrame(1000, "probe complete"));
                }
                // Give the peer a moment to answer the close frame, then finish regardless.
                setTimeout(finish, 250);
            }, waitSeconds * 1000);
        });

        req.end();
    });
}
