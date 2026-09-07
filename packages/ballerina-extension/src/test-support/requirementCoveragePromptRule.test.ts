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
 * Pins the requirement-coverage rule and its presence at both completion points of the system prompt.
 * `prompts.ts` cannot be imported here (extension-host module graph), so wiring is checked in the source.
 */

import * as fs from 'fs';
import * as path from 'path';
import { REQUIREMENT_COVERAGE_RULE } from '../features/ai/agent/requirement-coverage';

const promptsSource = fs.readFileSync(path.join(__dirname, '../features/ai/agent/prompts.ts'), 'utf-8');

describe('REQUIREMENT_COVERAGE_RULE content', () => {
    it('defeats the clean-compile-means-done signal', () => {
        expect(REQUIREMENT_COVERAGE_RULE).toMatch(/^Compilation is not evidence of completeness\./);
    });

    it('asks for an internal mapping of every behaviour, including both sides of "when X or Y"', () => {
        expect(REQUIREMENT_COVERAGE_RULE).toMatch(/\(do NOT print the list\)/);
        expect(REQUIREMENT_COVERAGE_RULE).toMatch(/each endpoint, trigger, event, status and BOTH sides of any "when X or Y" phrase/);
        expect(REQUIREMENT_COVERAGE_RULE).toMatch(/name the code path that implements it/);
    });

    it('requires an event fired from several operations to be wired into every handler', () => {
        expect(REQUIREMENT_COVERAGE_RULE).toMatch(/wire it into EVERY handler, not just one/);
    });

    it('names the declared-but-unreferenced symbol as the tell', () => {
        expect(REQUIREMENT_COVERAGE_RULE).toMatch(/constant, enum member, type or function you declared but never referenced/);
    });

    it('contains no unresolved interpolation', () => {
        expect(REQUIREMENT_COVERAGE_RULE).not.toMatch(/\$\{/);
    });
});

describe('system prompt wiring', () => {
    it('imports the rule', () => {
        expect(promptsSource).toMatch(/import \{ REQUIREMENT_COVERAGE_RULE \} from "\.\/requirement-coverage";/);
    });

    it('interpolates the rule once in the Plan-mode task completion bullet and once in the Edit-mode validation step', () => {
        const editMode = promptsSource.indexOf('## Edit Mode');
        const planCompletion = promptsSource.indexOf('Before marking the task as completed');
        const editStep4 = promptsSource.indexOf('### Step 4: Validate the code');
        const editStep5 = promptsSource.indexOf('### Step 5');
        const occurrences = [...promptsSource.matchAll(/\$\{REQUIREMENT_COVERAGE_RULE\}/g)].map((m) => m.index ?? -1);
        expect(occurrences).toHaveLength(2);
        expect(occurrences[0]).toBeGreaterThan(planCompletion);
        expect(occurrences[0]).toBeLessThan(editMode);
        expect(occurrences[1]).toBeGreaterThan(editStep4);
        expect(occurrences[1]).toBeLessThan(editStep5);
    });

    it('asks Plan mode to repeat the check across the whole request before the final response', () => {
        expect(promptsSource).toMatch(/\$\{REQUIREMENT_COVERAGE_RULE\} Repeat the check across the whole request before the final response\./);
    });
});
