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
 * Pins the prerequisites rule and its wiring into the system prompt. `prompts.ts` cannot be imported here
 * (it pulls the extension-host module graph), so the wiring is checked against the source text, the same
 * way the file's other rule sections are guarded.
 */

import * as fs from 'fs';
import * as path from 'path';
import { EXTERNAL_PREREQUISITES_RULE } from '../features/ai/agent/external-prerequisites';

const promptsSource = fs.readFileSync(path.join(__dirname, '../features/ai/agent/prompts.ts'), 'utf-8');

describe('EXTERNAL_PREREQUISITES_RULE content', () => {
    it('asks for a Prerequisites list scoped to resources the code binds but does not create', () => {
        expect(EXTERNAL_PREREQUISITES_RULE).toMatch(/does not create/);
        expect(EXTERNAL_PREREQUISITES_RULE).toMatch(/\*\*Prerequisites\*\*/);
        expect(EXTERNAL_PREREQUISITES_RULE).toMatch(/queues, topics, endpoints/);
        expect(EXTERNAL_PREREQUISITES_RULE).toMatch(/only those that actually appear in the code or its configurables/);
    });

    it('forbids invented setup steps and roots the list in the library README/instructions', () => {
        expect(EXTERNAL_PREREQUISITES_RULE).toMatch(/library's README and instructions/);
        expect(EXTERNAL_PREREQUISITES_RULE).toMatch(/never invent setup steps/);
    });

    it('contains no unresolved interpolation', () => {
        expect(EXTERNAL_PREREQUISITES_RULE).not.toMatch(/\$\{/);
    });
});

describe('system prompt wiring', () => {
    it('imports the rule', () => {
        expect(promptsSource).toMatch(/import \{ EXTERNAL_PREREQUISITES_RULE \} from "\.\/external-prerequisites";/);
    });

    it('interpolates the rule in the Edit-mode summary step and the Plan-mode user-communication rules', () => {
        const editStep = promptsSource.indexOf('### Step 5: Provide a consise summary');
        const planComms = promptsSource.indexOf('**User Communication**:');
        const editMode = promptsSource.indexOf('## Edit Mode');
        expect(planComms).toBeGreaterThan(-1);
        expect(editStep).toBeGreaterThan(editMode);

        const occurrences = [...promptsSource.matchAll(/\$\{EXTERNAL_PREREQUISITES_RULE\}/g)].map((m) => m.index ?? -1);
        expect(occurrences).toHaveLength(2);
        // One in Plan mode (before the Edit Mode heading), one in the Edit-mode summary step (after it).
        expect(occurrences[0]).toBeGreaterThan(planComms);
        expect(occurrences[0]).toBeLessThan(editMode);
        expect(occurrences[1]).toBeGreaterThan(editStep);
    });

    it('frames README setup statements as binding rather than generic information', () => {
        expect(promptsSource).toMatch(/Treat its setup and prerequisite statements/);
        expect(promptsSource).toMatch(/what the library does NOT create or manage/);
        expect(promptsSource).not.toMatch(/This is generic information about the library\./);
    });
});
