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
 * Pins the no-I/O-inside-lock rule and the exact fan-out snippet it teaches. The snippet was compiled
 * against Ballerina 2201.13.4 before being committed; these assertions keep it from drifting into one of the
 * variants (`toArray()` snapshot, `.clone()` on callers, bare `keys()`) that fail isolation analysis.
 */

import * as fs from 'fs';
import * as path from 'path';
import { LOCK_IO_RULE } from '../features/ai/agent/lock-io-rules';

const promptsSource = fs.readFileSync(path.join(__dirname, '../features/ai/agent/prompts.ts'), 'utf-8');

describe('LOCK_IO_RULE content', () => {
    it('forbids I/O and remote calls inside a lock and says why', () => {
        expect(LOCK_IO_RULE).toMatch(/^Never perform network I\/O or remote calls/);
        expect(LOCK_IO_RULE).toMatch(/one slow or dead peer then blocks every other caller/);
    });

    it('states the transfer-out rule precisely: single isolated object yes, array or map of them no', () => {
        expect(LOCK_IO_RULE).toMatch(/a `readonly` value or a SINGLE isolated object such as one `websocket:Caller`/);
        expect(LOCK_IO_RULE).toMatch(/An ARRAY or MAP of callers is NOT isolated/);
        expect(LOCK_IO_RULE).toMatch(/`lock \{ targets = subscribers\.toArray\(\); \}` fails to compile \(BCE3959\)/);
        expect(LOCK_IO_RULE).toMatch(/`\.clone\(\)` does not apply to objects/);
    });

    it('teaches the compiled fan-out pattern verbatim', () => {
        expect(LOCK_IO_RULE).toContain('connectionIds = subscribers.keys().clone();');
        expect(LOCK_IO_RULE).toContain('caller = subscribers[connectionId];');
        expect(LOCK_IO_RULE).toContain('websocket:Error? result = caller->writeMessage(event); // I/O OUTSIDE the lock');
        expect(LOCK_IO_RULE).toContain('_ = subscribers.removeIfHasKey(connectionId);');
        expect(LOCK_IO_RULE).toMatch(/isolated function broadcast\(readonly & OrderUpdate event\)/);
    });

    it('never puts the write inside a lock in the snippet', () => {
        const snippet = LOCK_IO_RULE.slice(LOCK_IO_RULE.indexOf('```ballerina'), LOCK_IO_RULE.lastIndexOf('```'));
        // Every lock block in the snippet is a single statement that touches only the map.
        const lockBodies = [...snippet.matchAll(/lock \{\n([\s\S]*?)\n\s*\}/g)].map((m) => m[1]);
        expect(lockBodies).toHaveLength(3);
        for (const body of lockBodies) {
            expect(body).not.toMatch(/->/);
            expect(body).toMatch(/subscribers/);
        }
    });

    it('contains no unresolved interpolation', () => {
        expect(LOCK_IO_RULE).not.toMatch(/\$\{/);
    });
});

describe('system prompt wiring', () => {
    it('imports the rule and places it at the end of the Coding Rules', () => {
        expect(promptsSource).toMatch(/import \{ LOCK_IO_RULE \} from "\.\/lock-io-rules";/);
        const codingRules = promptsSource.indexOf('## Coding Rules');
        const interpolation = promptsSource.indexOf('${LOCK_IO_RULE}');
        const fileMods = promptsSource.indexOf('## File modifications');
        expect(interpolation).toBeGreaterThan(codingRules);
        expect(interpolation).toBeLessThan(fileMods);
    });
});
