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
 * The Hurl tool must refuse WebSocket scripts (Hurl cannot complete a WebSocket session, and its timeouts
 * were being read as a working upgrade), while leaving every plain-HTTP script alone — including scripts
 * whose bodies or assertions merely mention WebSockets.
 */

import { detectWebSocketUsage, websocketRejectionMessage } from '../features/ai/agent/tools/hurl-websocket-guard';

describe('detectWebSocketUsage', () => {
    it('flags a ws:// or wss:// request URL', () => {
        expect(detectWebSocketUsage('GET ws://localhost:9090/orders/track?orderId=ORD-1')).toEqual({
            line: 1,
            reason: "request URL 'ws://localhost:9090/orders/track?orderId=ORD-1' uses the ws scheme",
        });
        expect(detectWebSocketUsage('POST http://localhost:8080/api\n\n{"a":1}\n\nGET WSS://localhost:9090/feed')?.line).toBe(5);
    });

    it('flags an Upgrade: websocket or Sec-WebSocket-Key header in a request header block', () => {
        expect(detectWebSocketUsage('GET http://localhost:9090/chat\nConnection: Upgrade\nUpgrade: websocket\n')).toEqual({
            line: 3,
            reason: "the request carries an 'Upgrade: websocket' header",
        });
        expect(detectWebSocketUsage('GET http://localhost:9090/chat\nsec-websocket-key: dGhlIHNhbXBsZSBub25jZQ==\n')?.reason)
            .toBe("the request carries a 'Sec-WebSocket-Key' header");
    });

    it.each([
        ['a plain GET', 'GET http://localhost:9090/api/v1/tickets\nAccept: application/json'],
        ['a JSON body that mentions a ws:// URL', 'POST http://localhost:8080/api/config\nContent-Type: application/json\n\n{"endpoint": "ws://localhost:9090/feed", "upgrade": "websocket"}'],
        ['a raw triple-backtick body carrying upgrade headers', 'POST http://localhost:8080/upload\n\n```\nGET ws://x/y\nUpgrade: websocket\n```'],
        ['an assertion on the Upgrade header', 'GET http://localhost:9090/health\nHTTP 200\n[Asserts]\nheader "Upgrade" == "websocket"'],
        ['a header block that ended at a section', 'GET http://localhost:9090/health\n[Options]\nUpgrade: websocket'],
        ['an empty script', ''],
    ])('leaves %s alone', (_name, script) => {
        expect(detectWebSocketUsage(script)).toBeNull();
    });

    it('resets the header block between requests so a later header is attributed to its own request', () => {
        const script = 'GET http://localhost:9090/a\nAccept: */*\n\nGET http://localhost:9090/b\nUpgrade: websocket';
        expect(detectWebSocketUsage(script)?.line).toBe(5);
    });
});

describe('websocketRejectionMessage', () => {
    it('names the failure, says the script did not run, forbids the timeout-as-success reading and names the probe tool', () => {
        const message = websocketRejectionMessage({ line: 3, reason: 'the request carries an \'Upgrade: websocket\' header' }, 'websocketProbeTool');
        expect(message).toMatch(/Hurl is HTTP-only/);
        expect(message).toMatch(/line 3/);
        expect(message).toMatch(/was not executed/);
        expect(message).toMatch(/never evidence that the upgrade worked/);
        expect(message).toMatch(/Use the websocketProbeTool tool/);
    });
});
