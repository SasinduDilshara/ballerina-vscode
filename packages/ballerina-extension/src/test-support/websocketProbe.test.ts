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

/**
 * @jest-environment node
 *
 * The frame codec is checked against RFC 6455 byte layouts, and the probe is run against in-process
 * servers: one that upgrades and echoes, one that refuses the upgrade with a 500 (the shape a panic in a
 * Ballerina upgrade resource produces), one that never answers, and one that is not listening at all.
 */

import * as http from 'http';
import * as net from 'net';
import {
    computeAcceptKey,
    decodeClosePayload,
    decodeFrames,
    encodeCloseFrame,
    encodeFrame,
    encodeTextFrame,
    OPCODE_BINARY,
    OPCODE_CLOSE,
    OPCODE_PING,
    OPCODE_TEXT,
} from '../features/ai/agent/tools/websocket-frames';
import { isLoopbackUrl, probeWebSocket } from '../features/ai/agent/tools/websocket-probe-core';

describe('WebSocket frame codec', () => {
    it('computes the RFC 6455 §4.2.2 accept key for the RFC example', () => {
        expect(computeAcceptKey('dGhlIHNhbXBsZSBub25jZQ==')).toBe('s3pPLMBiTxaQ9kYGzzhZRbK+xOo=');
    });

    it('encodes an unmasked text frame with the RFC single-frame layout', () => {
        // RFC 6455 §5.7: "Hello" as a single unmasked text frame is 0x81 0x05 followed by the bytes.
        expect([...encodeTextFrame('Hello', false)]).toEqual([0x81, 0x05, 0x48, 0x65, 0x6c, 0x6c, 0x6f]);
    });

    it('masks client frames and the decoder unmasks them', () => {
        const encoded = encodeTextFrame('Hello');
        expect(encoded[0]).toBe(0x81);
        expect(encoded[1]).toBe(0x85); // MASK bit + length 5
        expect(encoded.length).toBe(2 + 4 + 5);
        const { frames, rest } = decodeFrames(encoded);
        expect(frames).toHaveLength(1);
        expect(frames[0].fin).toBe(true);
        expect(frames[0].opcode).toBe(OPCODE_TEXT);
        expect(frames[0].payload.toString('utf8')).toBe('Hello');
        expect(rest.length).toBe(0);
    });

    it('uses the 16-bit and 64-bit extended payload lengths', () => {
        const medium = Buffer.alloc(300, 0x41);
        const mediumFrame = encodeFrame(OPCODE_BINARY, medium, false);
        expect(mediumFrame[1]).toBe(126);
        expect(mediumFrame.readUInt16BE(2)).toBe(300);
        expect(decodeFrames(mediumFrame).frames[0].payload.equals(medium)).toBe(true);

        const large = Buffer.alloc(70_000, 0x42);
        const largeFrame = encodeFrame(OPCODE_BINARY, large, true);
        expect(largeFrame[1] & 0x7f).toBe(127);
        expect(Number(largeFrame.readBigUInt64BE(2))).toBe(70_000);
        expect(decodeFrames(largeFrame).frames[0].payload.equals(large)).toBe(true);
    });

    it('decodes several frames from one buffer and keeps a trailing partial frame as rest', () => {
        const a = encodeTextFrame('one', false);
        const b = encodeFrame(OPCODE_PING, Buffer.from('p'), false);
        const c = encodeTextFrame('three', false);
        const stream = Buffer.concat([a, b, c.subarray(0, 3)]);
        const { frames, rest } = decodeFrames(stream);
        expect(frames.map((f) => f.opcode)).toEqual([OPCODE_TEXT, OPCODE_PING]);
        expect(rest.equals(c.subarray(0, 3))).toBe(true);
        const { frames: tail } = decodeFrames(Buffer.concat([rest, c.subarray(3)]));
        expect(tail[0].payload.toString()).toBe('three');
    });

    it('round-trips close frames with code and reason', () => {
        const frame = encodeCloseFrame(1011, 'boom', false);
        const decoded = decodeFrames(frame).frames[0];
        expect(decoded.opcode).toBe(OPCODE_CLOSE);
        expect(decodeClosePayload(decoded.payload)).toEqual({ code: 1011, reason: 'boom' });
        expect(decodeClosePayload(Buffer.alloc(0))).toEqual({ code: 1005, reason: '' });
    });
});

describe('isLoopbackUrl', () => {
    it.each([
        'ws://localhost:9090/x', 'wss://127.0.0.1:9443/x', 'ws://127.5.5.5/x', 'ws://[::1]:9090/x', 'ws://0.0.0.0:9090/x',
    ])('accepts %s', (url) => {
        expect(isLoopbackUrl(url)).toBe(true);
    });

    it.each(['ws://example.com/x', 'ws://10.0.0.5:9090/x', 'wss://api.github.com/', 'nonsense'])('rejects %s', (url) => {
        expect(isLoopbackUrl(url)).toBe(false);
    });
});

/**
 * Closes a server even when a peer left a socket half-open: an HTTP server's sockets allow half-open and
 * a silent `net` server never reads, so `server.close()` alone would wait forever on either.
 */
function closeServer(server: net.Server, sockets: Set<net.Socket>): Promise<void> {
    for (const socket of sockets) {
        socket.destroy();
    }
    return new Promise<void>((done) => server.close(() => done()));
}

function trackSockets(server: net.Server): Set<net.Socket> {
    const sockets = new Set<net.Socket>();
    server.on('connection', (socket: net.Socket) => {
        sockets.add(socket);
        socket.on('close', () => sockets.delete(socket));
    });
    return sockets;
}

async function listen(server: net.Server): Promise<number> {
    await new Promise<void>((r) => server.listen(0, '127.0.0.1', () => r()));
    return (server.address() as net.AddressInfo).port;
}

/** An in-process server that completes the handshake and echoes text frames, prefixed, until closed. */
function startEchoServer(): Promise<{ port: number; close: () => Promise<void>; received: string[] }> {
    const received: string[] = [];
    const server = http.createServer((_req, res) => {
        res.writeHead(426, { Upgrade: 'websocket' });
        res.end('upgrade required');
    });
    const sockets = trackSockets(server);
    server.on('upgrade', (req, socket: net.Socket) => {
        const key = req.headers['sec-websocket-key'] as string;
        socket.write(
            'HTTP/1.1 101 Switching Protocols\r\n'
            + 'Upgrade: websocket\r\n'
            + 'Connection: Upgrade\r\n'
            + `Sec-WebSocket-Accept: ${computeAcceptKey(key)}\r\n\r\n`,
        );
        // Push one frame unprompted, the way a service pushing status updates does.
        socket.write(encodeTextFrame('welcome', false));
        let pending = Buffer.alloc(0);
        socket.on('data', (chunk: Buffer) => {
            pending = Buffer.concat([pending, chunk]);
            const { frames, rest } = decodeFrames(pending);
            pending = rest;
            for (const frame of frames) {
                if (frame.opcode === OPCODE_TEXT) {
                    const text = frame.payload.toString('utf8');
                    received.push(text);
                    socket.write(encodeTextFrame(`echo:${text}`, false));
                } else if (frame.opcode === OPCODE_CLOSE) {
                    socket.write(encodeCloseFrame(1000, 'bye', false));
                    socket.end();
                }
            }
        });
        socket.on('error', () => undefined);
    });
    return listen(server).then((port) => ({ port, received, close: () => closeServer(server, sockets) }));
}

describe('probeWebSocket', () => {
    it('upgrades, sends the messages in order and collects the frames the server pushes', async () => {
        const server = await startEchoServer();
        try {
            const result = await probeWebSocket({
                url: `ws://127.0.0.1:${server.port}/tickets/live?queue=hana`,
                messages: ['{"action":"REPLACE","queues":["hana"]}', '{"action":"SUBSCRIBE","queues":["nora"]}'],
                waitSeconds: 0.3,
            });
            expect(result.upgraded).toBe(true);
            expect(result.statusCode).toBe(101);
            expect(result.error).toBeUndefined();
            expect(result.framesSent).toBe(2);
            expect(result.framesReceived.map((f) => f.data)).toEqual([
                'welcome',
                'echo:{"action":"REPLACE","queues":["hana"]}',
                'echo:{"action":"SUBSCRIBE","queues":["nora"]}',
            ]);
            expect(server.received).toEqual(['{"action":"REPLACE","queues":["hana"]}', '{"action":"SUBSCRIBE","queues":["nora"]}']);
            // The probe closed the connection with a close frame and the server answered it.
            expect(result.closeCode).toBe(1000);
            expect(result.closeReason).toBe('bye');
            expect(result.timedOut).toBe(false);
        } finally {
            await server.close();
        }
    });

    it('reports a refused upgrade with the HTTP status and body instead of treating it as a pass', async () => {
        const server = http.createServer((_req, res) => {
            res.writeHead(500, { 'content-type': 'text/plain' });
            res.end("{ballerina}TypeCastError {\"message\":\"incompatible types: 'jwt:Payload' cannot be cast to 'map<json>'\"}");
        });
        const sockets = trackSockets(server);
        const port = await listen(server);
        try {
            const result = await probeWebSocket({ url: `ws://localhost:${port}/orders/track?orderId=ORD-1`, waitSeconds: 1 });
            expect(result.upgraded).toBe(false);
            expect(result.statusCode).toBe(500);
            expect(result.responseBody).toContain('TypeCastError');
            expect(result.error).toMatch(/refused the WebSocket upgrade with HTTP 500/);
            expect(result.framesReceived).toEqual([]);
        } finally {
            await closeServer(server, sockets);
        }
    });

    it('times out when the server never answers the handshake, and says so', async () => {
        const server = net.createServer(() => { /* accept and stay silent */ });
        const sockets = trackSockets(server);
        const port = await listen(server);
        try {
            const result = await probeWebSocket({ url: `ws://127.0.0.1:${port}/`, waitSeconds: 1, connectTimeoutMs: 300 });
            expect(result.upgraded).toBe(false);
            expect(result.timedOut).toBe(true);
            expect(result.error).toMatch(/No handshake response within 300 ms/);
        } finally {
            await closeServer(server, sockets);
        }
    });

    it('reports a refused connection when nothing is listening', async () => {
        const probe = net.createServer();
        const port = await listen(probe);
        await closeServer(probe, new Set());

        const result = await probeWebSocket({ url: `ws://127.0.0.1:${port}/`, waitSeconds: 1 });
        expect(result.upgraded).toBe(false);
        expect(result.error).toMatch(/Connection refused/);
    });

    it('refuses to probe anything but loopback hosts', async () => {
        const result = await probeWebSocket({ url: 'wss://echo.websocket.events/', waitSeconds: 1 });
        expect(result.upgraded).toBe(false);
        expect(result.error).toMatch(/only connects to services on this machine/);
    });

    it('rejects a server whose accept key does not match', async () => {
        const server = http.createServer();
        server.on('upgrade', (_req, socket: net.Socket) => {
            socket.write('HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: bogus=\r\n\r\n');
        });
        const sockets = trackSockets(server);
        const port = await listen(server);
        try {
            const result = await probeWebSocket({ url: `ws://127.0.0.1:${port}/`, waitSeconds: 1 });
            expect(result.upgraded).toBe(false);
            expect(result.statusCode).toBe(101);
            expect(result.error).toMatch(/Sec-WebSocket-Accept header does not match/);
        } finally {
            await closeServer(server, sockets);
        }
    });
});
