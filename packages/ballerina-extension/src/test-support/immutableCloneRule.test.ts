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
 * Pins the clone-of-immutable rule. Both remedies it teaches were run on Ballerina 2201.13.4; the
 * assertions keep the exact forms (query expression with a trailing `.clone()`, `check ... cloneWithType()`)
 * from drifting into variants that either stay readonly or fail isolation analysis.
 */

import * as fs from 'fs';
import * as path from 'path';
import { IMMUTABLE_CLONE_RULE } from '../features/ai/agent/immutable-clone-rules';

const promptsSource = fs.readFileSync(path.join(__dirname, '../features/ai/agent/prompts.ts'), 'utf-8');

describe('IMMUTABLE_CLONE_RULE content', () => {
    it('states that cloning an immutable value returns the same immutable value, with the spec basis', () => {
        expect(IMMUTABLE_CLONE_RULE).toMatch(/^`clone\(\)` and `cloneReadOnly\(\)` on a value that is ALREADY immutable return the SAME immutable value/);
        expect(IMMUTABLE_CLONE_RULE).toMatch(/the spec defines Clone\(v\) for an immutable v as v/);
    });

    it('names the runtime panic and says diagnostics cannot catch it', () => {
        expect(IMMUTABLE_CLONE_RULE).toMatch(/`\{ballerina\/lang\.array\}InvalidUpdate` \("modification not allowed on readonly value"\)/);
        expect(IMMUTABLE_CLONE_RULE).toMatch(/Diagnostics cannot catch this/);
    });

    it('teaches the verified remedies in their exact forms', () => {
        expect(IMMUTABLE_CLONE_RULE).toContain('`string[] fresh = (from string q in roQueues select q).clone();`');
        expect(IMMUTABLE_CLONE_RULE).toContain('`string[] fresh = check roQueues.cloneWithType();`');
        expect(IMMUTABLE_CLONE_RULE).toMatch(/a bare query expression there is a compile error/);
        expect(IMMUTABLE_CLONE_RULE).toMatch(/in a function that can return `error`/);
    });

    it('closes with the storage rule', () => {
        expect(IMMUTABLE_CLONE_RULE).toMatch(/Never store a readonly-derived value where later code will mutate it\.$/);
    });

    it('contains no unresolved interpolation', () => {
        expect(IMMUTABLE_CLONE_RULE).not.toMatch(/\$\{/);
    });
});

describe('system prompt wiring', () => {
    it('imports the rule and places it at the end of the Coding Rules', () => {
        expect(promptsSource).toMatch(/import \{ IMMUTABLE_CLONE_RULE \} from "\.\/immutable-clone-rules";/);
        const codingRules = promptsSource.indexOf('## Coding Rules');
        const interpolation = promptsSource.indexOf('${IMMUTABLE_CLONE_RULE}');
        const fileMods = promptsSource.indexOf('## File modifications');
        expect(interpolation).toBeGreaterThan(codingRules);
        expect(interpolation).toBeLessThan(fileMods);
    });
});
