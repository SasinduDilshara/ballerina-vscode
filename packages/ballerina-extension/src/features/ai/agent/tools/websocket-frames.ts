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
 * The small part of RFC 6455 a verification probe needs: the opening-handshake key exchange and the data
 * framing (text, binary, close, ping, pong; 7/16/64-bit payload lengths; client-side masking).
 *
 * Written against the RFC rather than pulled in as a dependency: the extension host's Node runtime has no
 * usable WebSocket client (the global exists only behind an experimental flag and reports no HTTP status
 * on a refused upgrade), and a probe whose whole purpose is to report *why* an upgrade failed needs the
 * raw `http` response anyway. Pure functions over Buffers, so the codec is unit tested in isolation.
 */

import * as crypto from "crypto";

/** The GUID RFC 6455 §4.2.2 appends to the client key before hashing. */
const WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

export const OPCODE_CONTINUATION = 0x0;
export const OPCODE_TEXT = 0x1;
export const OPCODE_BINARY = 0x2;
export const OPCODE_CLOSE = 0x8;
export const OPCODE_PING = 0x9;
export const OPCODE_PONG = 0xa;

export interface WebSocketFrame {
    fin: boolean;
    opcode: number;
    payload: Buffer;
}

/** A fresh `Sec-WebSocket-Key`: 16 random bytes, base64-encoded (RFC 6455 §4.1). */
export function createHandshakeKey(): string {
    return crypto.randomBytes(16).toString("base64");
}

/** The `Sec-WebSocket-Accept` value a compliant server must answer the given key with (RFC 6455 §4.2.2). */
export function computeAcceptKey(handshakeKey: string): string {
    return crypto.createHash("sha1").update(handshakeKey + WEBSOCKET_GUID).digest("base64");
}

/**
 * Encodes one frame. Client-to-server frames MUST be masked (RFC 6455 §5.1); `mask` defaults to true so
 * the probe cannot forget, and a test server passes `false` for its own frames.
 */
export function encodeFrame(opcode: number, payload: Buffer, mask: boolean = true): Buffer {
    const length = payload.length;
    let header: Buffer;
    if (length < 126) {
        header = Buffer.alloc(2);
        header[1] = length;
    } else if (length <= 0xffff) {
        header = Buffer.alloc(4);
        header[1] = 126;
        header.writeUInt16BE(length, 2);
    } else {
        header = Buffer.alloc(10);
        header[1] = 127;
        header.writeBigUInt64BE(BigInt(length), 2);
    }
    header[0] = 0x80 | (opcode & 0x0f); // FIN set, RSV clear

    if (!mask) {
        return Buffer.concat([header, payload]);
    }
    header[1] |= 0x80;
    const maskingKey = crypto.randomBytes(4);
    const masked = Buffer.alloc(length);
    for (let i = 0; i < length; i++) {
        masked[i] = payload[i] ^ maskingKey[i % 4];
    }
    return Buffer.concat([header, maskingKey, masked]);
}

export function encodeTextFrame(text: string, mask: boolean = true): Buffer {
    return encodeFrame(OPCODE_TEXT, Buffer.from(text, "utf8"), mask);
}

/** A close frame carrying a status code and an optional UTF-8 reason (RFC 6455 §5.5.1). */
export function encodeCloseFrame(code: number = 1000, reason: string = "", mask: boolean = true): Buffer {
    const reasonBytes = Buffer.from(reason, "utf8");
    const payload = Buffer.alloc(2 + reasonBytes.length);
    payload.writeUInt16BE(code, 0);
    reasonBytes.copy(payload, 2);
    return encodeFrame(OPCODE_CLOSE, payload, mask);
}

/** The status code and reason inside a close frame's payload; a payload with no code reads as 1005. */
export function decodeClosePayload(payload: Buffer): { code: number; reason: string } {
    if (payload.length < 2) {
        return { code: 1005, reason: "" };
    }
    return { code: payload.readUInt16BE(0), reason: payload.subarray(2).toString("utf8") };
}

/**
 * Decodes every complete frame at the start of `buffer` and returns the bytes that did not yet form a
 * complete frame, so a stream can be fed chunk by chunk. Masked frames (from a client) are unmasked.
 */
export function decodeFrames(buffer: Buffer): { frames: WebSocketFrame[]; rest: Buffer } {
    const frames: WebSocketFrame[] = [];
    let offset = 0;

    while (buffer.length - offset >= 2) {
        const byte0 = buffer[offset];
        const byte1 = buffer[offset + 1];
        const fin = (byte0 & 0x80) !== 0;
        const opcode = byte0 & 0x0f;
        const masked = (byte1 & 0x80) !== 0;
        let length = byte1 & 0x7f;
        let cursor = offset + 2;

        if (length === 126) {
            if (buffer.length - cursor < 2) {
                break;
            }
            length = buffer.readUInt16BE(cursor);
            cursor += 2;
        } else if (length === 127) {
            if (buffer.length - cursor < 8) {
                break;
            }
            const big = buffer.readBigUInt64BE(cursor);
            if (big > BigInt(Number.MAX_SAFE_INTEGER)) {
                throw new Error("WebSocket frame payload length exceeds the supported size");
            }
            length = Number(big);
            cursor += 8;
        }

        let maskingKey: Buffer | undefined;
        if (masked) {
            if (buffer.length - cursor < 4) {
                break;
            }
            maskingKey = buffer.subarray(cursor, cursor + 4);
            cursor += 4;
        }

        if (buffer.length - cursor < length) {
            break;
        }
        const payload = Buffer.from(buffer.subarray(cursor, cursor + length));
        if (maskingKey) {
            for (let i = 0; i < payload.length; i++) {
                payload[i] ^= maskingKey[i % 4];
            }
        }
        frames.push({ fin, opcode, payload });
        offset = cursor + length;
    }

    return { frames, rest: Buffer.from(buffer.subarray(offset)) };
}
