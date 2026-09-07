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
 * Runtime panics in service output are the reason clients saw HTTP 500 or a dropped connection. The traces
 * below are the ones observed in testing (a TypeCastError in a WebSocket upgrade resource; an InvalidUpdate
 * in onMessage) as the Ballerina runtime prints them.
 */

import * as fs from 'fs';
import * as path from 'path';
import { buildRuntimePanicNote, detectRuntimePanics } from '../features/ai/agent/tools/runtime-panics';

const promptsSource = fs.readFileSync(path.join(__dirname, '../features/ai/agent/prompts.ts'), 'utf-8');

const TYPE_CAST_TRACE = [
    'Compiling source',
    '\tdamsith/order_tracker:0.1.0',
    'Running executable',
    '',
    "error: {ballerina}TypeCastError {\"message\":\"incompatible types: 'jwt:Payload' cannot be cast to 'map<json>'\"}",
    '\tat damsith.order_tracker.0:authorizeOrderAccess(main.bal:65)',
    '\t   damsith.order_tracker.0.$anonType$_0:$get$.(main.bal:30)',
    '',
].join('\n');

const INVALID_UPDATE_TRACE = [
    'error: {ballerina/lang.array}InvalidUpdate {"message":"modification not allowed on readonly value"}',
    '\tat ballerina.lang.array.0:push(array.bal:419)',
    '\t   damsith.support_desk.0:updateTicketSubscription(ticket_events.bal:76)',
    '\t   damsith.support_desk.0.TicketLiveService:onMessage(main.bal:132)',
].join('\n');

describe('detectRuntimePanics', () => {
    it('extracts the error type, message, innermost user frame and location from a TypeCastError trace', () => {
        const [panic] = detectRuntimePanics(TYPE_CAST_TRACE);
        expect(panic.errorType).toBe('{ballerina}TypeCastError');
        expect(panic.message).toBe("incompatible types: 'jwt:Payload' cannot be cast to 'map<json>'");
        expect(panic.function).toBe('authorizeOrderAccess');
        expect(panic.location).toBe('main.bal:65');
        expect(panic.frames).toEqual([
            'damsith.order_tracker.0:authorizeOrderAccess(main.bal:65)',
            'damsith.order_tracker.0.$anonType$_0:$get$.(main.bal:30)',
        ]);
    });

    it('takes the innermost frame even when it is a langlib frame', () => {
        const [panic] = detectRuntimePanics(INVALID_UPDATE_TRACE);
        expect(panic.errorType).toBe('{ballerina/lang.array}InvalidUpdate');
        expect(panic.message).toBe('modification not allowed on readonly value');
        expect(panic.function).toBe('push');
        expect(panic.location).toBe('array.bal:419');
        expect(panic.frames).toHaveLength(3);
    });

    it('finds every panic in a page of output and ignores ordinary log lines', () => {
        const output = `time=2026-09-04 level=INFO module=x message="ticket created"\n${TYPE_CAST_TRACE}\nerror: something unrelated without a type\n${INVALID_UPDATE_TRACE}\n`;
        const panics = detectRuntimePanics(output);
        expect(panics.map((p) => p.errorType)).toEqual(['{ballerina}TypeCastError', '{ballerina/lang.array}InvalidUpdate']);
    });

    it('keeps a non-JSON detail verbatim and copes with a trace that has no frames', () => {
        const [panic] = detectRuntimePanics('error: {ballerina}DivisionByZero / by zero');
        expect(panic.message).toBe('/ by zero');
        expect(panic.location).toBeUndefined();
        expect(panic.frames).toEqual([]);
    });

    it('returns nothing for clean output', () => {
        expect(detectRuntimePanics('Running executable\n\ntime=... level=INFO message="started"\n')).toEqual([]);
        expect(detectRuntimePanics('')).toEqual([]);
    });
});

describe('buildRuntimePanicNote', () => {
    it('is empty when there are no panics', () => {
        expect(buildRuntimePanicNote([])).toBe('');
    });

    it('names the type, function, location and message, and states the consequence', () => {
        const note = buildRuntimePanicNote(detectRuntimePanics(TYPE_CAST_TRACE));
        expect(note).toMatch(/1 runtime panic\(s\) detected/);
        expect(note).toMatch(/\{ballerina\}TypeCastError at authorizeOrderAccess \(main\.bal:65\): incompatible types/);
        expect(note).toMatch(/clients receive HTTP 500 or lose their connection/);
        expect(note).toMatch(/do not treat the service as working/);
    });
});

describe('system prompt wiring', () => {
    it('tells the model to read runtimePanics from the logs tool when clients see a 500', () => {
        expect(promptsSource).toMatch(/When a client receives HTTP 500 or a dropped connection from a running service, read the service output with \$\{BALLERINA_GET_LOGS_TOOL_NAME\}/);
        expect(promptsSource).toMatch(/never treat the service as working while its output shows a panic/);
        expect(promptsSource).toMatch(/import \{ BALLERINA_GET_LOGS_TOOL_NAME \} from "\.\/tools\/ballerina-get-logs";/);
    });
});
