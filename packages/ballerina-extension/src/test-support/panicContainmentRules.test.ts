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
 * Pins the panic-containment rules: the process-level consequence, the upgrade-resource exception, the
 * error-returning constructs preferred over trap, and the single boundary trap. `prompts.ts` cannot be
 * imported here (extension-host module graph), so wiring is checked in the source.
 */

import * as fs from 'fs';
import * as path from 'path';
import { PANIC_CONTAINMENT_RULES } from '../features/ai/agent/panic-containment-rules';

const promptsSource = fs.readFileSync(path.join(__dirname, '../features/ai/agent/prompts.ts'), 'utf-8');

describe('PANIC_CONTAINMENT_RULES content', () => {
    it('states that an escaped panic exits the whole process and that a 500 is not evidence of survival', () => {
        expect(PANIC_CONTAINMENT_RULES.startsWith('## Panic containment in services')).toBe(true);
        expect(PANIC_CONTAINMENT_RULES).toMatch(/the WHOLE PROCESS EXITS/);
        expect(PANIC_CONTAINMENT_RULES).toMatch(/a contained-looking response is not evidence the server survived/);
        expect(PANIC_CONTAINMENT_RULES).toMatch(/Returning an `error` is safe/);
    });

    it('states the upgrade-resource exception with the unlogged-500 symptom', () => {
        expect(PANIC_CONTAINMENT_RULES).toMatch(/in a WebSocket upgrade `get` resource a panic does not kill the process/);
        expect(PANIC_CONTAINMENT_RULES).toMatch(/answers the handshake with HTTP 500 and prints the panic only to stderr/);
        expect(PANIC_CONTAINMENT_RULES).toMatch(/never let a panic reach the upgrade resource either/);
    });

    it('prefers error-returning constructs, names checkpanic and the cast, and does not call cloneWithType a hazard', () => {
        expect(PANIC_CONTAINMENT_RULES).toMatch(/`check` \(never `checkpanic`\)/);
        expect(PANIC_CONTAINMENT_RULES).toMatch(/`x is T`, `x\.ensureType\(T\)` or `cloneWithType` instead of the cast `<T>x`/);
        expect(PANIC_CONTAINMENT_RULES).toMatch(/This — not `trap` — is the primary defence/);
    });

    it('asks for exactly one trap at the callback boundary, not blanket trapping', () => {
        expect(PANIC_CONTAINMENT_RULES).toMatch(/add ONE `trap` at the callback boundary/);
        expect(PANIC_CONTAINMENT_RULES).toMatch(/`T\|error r = trap doWork\(\.\.\.\); if r is error \{ log:printError\("\.\.\.", 'error = r\); return r; \}`/);
        expect(PANIC_CONTAINMENT_RULES).toMatch(/do not scatter `trap` over individual statements/);
    });

    it('contains no unresolved interpolations or stray backtick escapes', () => {
        expect(PANIC_CONTAINMENT_RULES).not.toMatch(/\$\{/);
        expect(PANIC_CONTAINMENT_RULES).not.toMatch(/\\`/);
    });
});

describe('system prompt wiring', () => {
    it('imports the rules and places them between Coding Rules and File modifications', () => {
        expect(promptsSource).toMatch(/import \{ PANIC_CONTAINMENT_RULES \} from "\.\/panic-containment-rules";/);
        const codingRules = promptsSource.indexOf('## Coding Rules');
        const interpolation = promptsSource.indexOf('${PANIC_CONTAINMENT_RULES}');
        const fileMods = promptsSource.indexOf('## File modifications');
        expect(interpolation).toBeGreaterThan(codingRules);
        expect(interpolation).toBeLessThan(fileMods);
    });
});
