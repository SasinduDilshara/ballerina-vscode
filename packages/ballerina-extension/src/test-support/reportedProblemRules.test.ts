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
 * Pins the rule that a user-supplied cause is a hypothesis to verify, and its wiring into the system prompt.
 * `prompts.ts` cannot be imported here (extension-host module graph), so wiring is checked in the source.
 */

import * as fs from 'fs';
import * as path from 'path';
import { REPORTED_PROBLEM_RULES } from '../features/ai/agent/reported-problem-rules';

const promptsSource = fs.readFileSync(path.join(__dirname, '../features/ai/agent/prompts.ts'), 'utf-8');

describe('REPORTED_PROBLEM_RULES content', () => {
    it('is a markdown section that frames a suggested cause as a hypothesis', () => {
        expect(REPORTED_PROBLEM_RULES.startsWith('# Diagnosing reported problems')).toBe(true);
        expect(REPORTED_PROBLEM_RULES).toMatch(/treat the suggestion as a hypothesis, not a diagnosis/);
    });

    it('requires verification by reproduction, logs and library documentation', () => {
        expect(REPORTED_PROBLEM_RULES).toMatch(/reproduce the failure \(run the program, probe the endpoint, read the service logs\)/);
        expect(REPORTED_PROBLEM_RULES).toMatch(/check the library documentation and instructions/);
    });

    it('forbids restating an unverified hypothesis as the root cause and treating symptom removal as proof', () => {
        expect(REPORTED_PROBLEM_RULES).toMatch(/Never restate an unverified hypothesis as the root cause, in the response or in code comments/);
        expect(REPORTED_PROBLEM_RULES).toMatch(/A change that makes the symptom disappear is not proof of the cause/);
        expect(REPORTED_PROBLEM_RULES).toMatch(/say that the cause is unconfirmed/);
    });

    it('contains no unresolved interpolation', () => {
        expect(REPORTED_PROBLEM_RULES).not.toMatch(/\$\{/);
    });
});

describe('system prompt wiring', () => {
    it('imports the rules and places them right before the code generation guidelines', () => {
        expect(promptsSource).toMatch(/import \{ REPORTED_PROBLEM_RULES \} from "\.\/reported-problem-rules";/);
        const interpolation = promptsSource.indexOf('${REPORTED_PROBLEM_RULES}');
        const guidelines = promptsSource.indexOf('# Code Generation Guidelines');
        const clarifying = promptsSource.indexOf('# Clarifying Questions');
        expect(interpolation).toBeGreaterThan(clarifying);
        expect(interpolation).toBeLessThan(guidelines);
    });
});
