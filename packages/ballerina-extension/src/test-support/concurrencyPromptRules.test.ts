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
 * Pins the concurrency rules the system prompt teaches, in particular the spec §7.3.5 rule about
 * module-level `final` variables: readable from isolated code without a lock when the type is `readonly`
 * OR an isolated object. A rule that mentioned only the `readonly` half made the agent treat a `final`
 * instance of an `isolated class` as unusable from a service. `prompts.ts` cannot be imported here (it
 * pulls the extension-host module graph), so its wiring is checked against the source text.
 */

import * as fs from 'fs';
import * as path from 'path';
import { CONCURRENCY_CODING_RULES } from '../features/ai/agent/concurrency-rules';
import { DIAGNOSTIC_HINTS } from '../features/ai/agent/tools/diagnostic-hints';

const promptsSource = fs.readFileSync(path.join(__dirname, '../features/ai/agent/prompts.ts'), 'utf-8');

describe('CONCURRENCY_CODING_RULES content', () => {
    it('is a markdown section with the expected heading', () => {
        expect(CONCURRENCY_CODING_RULES.startsWith('## Concurrency Safety and Shared State')).toBe(true);
    });

    it('never suggests final on configurable variables (compile error)', () => {
        expect(CONCURRENCY_CODING_RULES).toMatch(/NEVER write `final configurable`/);
        expect(CONCURRENCY_CODING_RULES).toMatch(/implicitly final/);
    });

    it('teaches that a final variable is readable from isolated code when its type is readonly OR an isolated object', () => {
        expect(CONCURRENCY_CODING_RULES).toMatch(/never reassigned, declare it `final`/);
        expect(CONCURRENCY_CODING_RULES).toMatch(/WITHOUT a lock when its static type is immutable \(`readonly`/);
        expect(CONCURRENCY_CODING_RULES).toMatch(/OR an isolated object/);
        expect(CONCURRENCY_CODING_RULES).toMatch(/most connector clients and listeners/);
        expect(CONCURRENCY_CODING_RULES).toMatch(/`ai:Agent`/);
        expect(CONCURRENCY_CODING_RULES).toMatch(/instances of a class you declared `isolated class`/);
    });

    it('forbids the wrong repairs for a final isolated object', () => {
        expect(CONCURRENCY_CODING_RULES).toMatch(/Do NOT declare such a variable `isolated`/);
        expect(CONCURRENCY_CODING_RULES).toMatch(/do NOT wrap its use in `lock \{ \}`/);
        expect(CONCURRENCY_CODING_RULES).toMatch(/do NOT add `& readonly` to it/);
    });

    it('still scopes "final alone is not sufficient" to non-isolated mutable types', () => {
        expect(CONCURRENCY_CODING_RULES).toMatch(/`final` alone is not sufficient for any OTHER type/);
        expect(CONCURRENCY_CODING_RULES).toMatch(/`final map<string>` or `final int\[\]` is still non-isolated mutable state/);
    });

    it('teaches the isolated-variable + lock pattern for shared mutable state', () => {
        expect(CONCURRENCY_CODING_RULES).toMatch(/declare the variable `isolated`/);
        expect(CONCURRENCY_CODING_RULES).toMatch(/ONLY inside `lock \{ \}` blocks/);
        expect(CONCURRENCY_CODING_RULES).toMatch(/must be initialized at its declaration/);
    });

    it('tells the model to declare its own module-level object classes as isolated class', () => {
        expect(CONCURRENCY_CODING_RULES).toMatch(/declare it `isolated class`/);
        expect(CONCURRENCY_CODING_RULES).toMatch(/declare every mutable field `private`/);
    });

    it('teaches the one-root-per-lock rule and the transfer rule scoped to restricted locks', () => {
        expect(CONCURRENCY_CODING_RULES).toMatch(/only ONE isolated root/);
        expect(CONCURRENCY_CODING_RULES).toMatch(/copy it with `\.clone\(\)`/);
        expect(CONCURRENCY_CODING_RULES).toMatch(/single isolated objects \(a client, a caller\) may leave without cloning/);
        expect(CONCURRENCY_CODING_RULES).toMatch(/ordinary lock over non-isolated state has no transfer restrictions/);
    });

    it('forbids strand creation and worker message passing inside locks', () => {
        expect(CONCURRENCY_CODING_RULES).toMatch(/Never use `start`, named `worker` declarations, or worker send\/receive/);
    });

    it('contains no unresolved interpolations or stray backtick escapes', () => {
        expect(CONCURRENCY_CODING_RULES).not.toMatch(/\$\{/);
        expect(CONCURRENCY_CODING_RULES).not.toMatch(/\\`/);
    });
});

describe('system prompt wiring', () => {
    it('imports the rules constant', () => {
        expect(promptsSource).toMatch(/import \{ CONCURRENCY_CODING_RULES \} from "\.\/concurrency-rules";/);
    });

    it('interpolates the rules between Coding Rules and File modifications', () => {
        const codingRules = promptsSource.indexOf('## Coding Rules');
        const interpolation = promptsSource.indexOf('${CONCURRENCY_CODING_RULES}');
        const fileMods = promptsSource.indexOf('## File modifications');
        expect(codingRules).toBeGreaterThan(-1);
        expect(interpolation).toBeGreaterThan(codingRules);
        expect(interpolation).toBeLessThan(fileMods);
    });
});

describe('isolation diagnostic hints', () => {
    it('keeps the pre-existing missing-import hint', () => {
        expect(DIAGNOSTIC_HINTS.BCE2000).toMatch(/missing import/);
    });

    it('BCE3943 states both legal final types and the different-variable case, and forbids dropping isolated', () => {
        const hint = DIAGNOSTIC_HINTS.BCE3943;
        expect(hint).toMatch(/immutable \(`readonly`\) OR an isolated object/);
        expect(hint).toMatch(/instance of an `isolated class`/);
        expect(hint).toMatch(/the error comes from a DIFFERENT variable/);
        expect(hint).toMatch(/Do NOT simply drop the `isolated` qualifier/);
    });

    it('covers the lock rules the agent hits most and agrees with the prompt on the array-of-objects case', () => {
        for (const code of ['BCE3956', 'BCE3957', 'BCE3959', 'BCE3960', 'BCE3961', 'BCE3962', 'BCE3964', 'BCE3965']) {
            expect(typeof DIAGNOSTIC_HINTS[code]).toBe('string');
            expect(DIAGNOSTIC_HINTS[code].length).toBeGreaterThan(40);
        }
        expect(DIAGNOSTIC_HINTS.BCE3959).toMatch(/an ARRAY or MAP of them may not/);
        expect(DIAGNOSTIC_HINTS.BCE3964).toMatch(/only ONE isolated variable/);
    });
});
