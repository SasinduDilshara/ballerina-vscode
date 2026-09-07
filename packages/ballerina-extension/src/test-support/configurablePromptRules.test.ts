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
 * Pins the configurable-variable rules: the four allowed types, the declare-then-convert idioms for every
 * other type (each compiled and run on Ballerina 2201.13.4), the no-default rule with its refreshUrl
 * exception, and the wiring into the system prompt. `prompts.ts` cannot be imported here (extension-host
 * module graph), so wiring is checked in the source.
 */

import * as fs from 'fs';
import * as path from 'path';
import { CONFIGURABLE_CODING_RULES } from '../features/ai/agent/configurable-rules';

const promptsSource = fs.readFileSync(path.join(__dirname, '../features/ai/agent/prompts.ts'), 'utf-8');

describe('CONFIGURABLE_CODING_RULES content', () => {
    it('is a markdown section that limits configurables to the four collector-supported types', () => {
        expect(CONFIGURABLE_CODING_RULES.startsWith('## Configurable variables')).toBe(true);
        expect(CONFIGURABLE_CODING_RULES).toMatch(/ONLY be declared with one of these types: `string`, `int`, `decimal`, `boolean`/);
        expect(CONFIGURABLE_CODING_RULES).toMatch(/not `int:Signed32`\/`int:Unsigned8`\/`byte`, not `float`, not arrays \(`string\[\]`\), not enums, not records, maps, unions or optional/);
        expect(CONFIGURABLE_CODING_RULES).toMatch(/expected to be of type '\.\.\.' but found 'string'/);
    });

    it('teaches declare-with-an-allowed-type-then-convert, never widening the configurable type', () => {
        expect(CONFIGURABLE_CODING_RULES).toMatch(/STILL declare the configurable with the nearest allowed type and convert it at the point of use/);
        expect(CONFIGURABLE_CODING_RULES).toMatch(/Do NOT change the configurable's type to match the API/);
    });

    it('carries the verified conversion idioms verbatim', () => {
        expect(CONFIGURABLE_CODING_RULES).toContain('int:Signed32 retries = check maxRetries.ensureType();');
        expect(CONFIGURABLE_CODING_RULES).toContain('byte size = check bufferSize.ensureType();');
        expect(CONFIGURABLE_CODING_RULES).toContain('float timeout = <float>timeoutSeconds;');
        expect(CONFIGURABLE_CODING_RULES).toContain('LogLevel level = check logLevel.ensureType();');
        expect(CONFIGURABLE_CODING_RULES).toContain('string[] scopes = from string scope in re `,`.split(oauthScopes) let string trimmed = scope.trim() where trimmed.length() > 0 select trimmed;');
        expect(CONFIGURABLE_CODING_RULES).toMatch(/`ensureType` returns an error, not a panic, when the value is out of range/);
    });

    it('forbids array configurables and delimited strings passed where the API takes string[]', () => {
        expect(CONFIGURABLE_CODING_RULES).toMatch(/Never declare `configurable string\[\]`/);
        expect(CONFIGURABLE_CODING_RULES).toMatch(/never build a colon- or space-delimited string where the API takes `string\[\]`/);
    });

    it('keeps the no-default rule and the single refreshUrl exception', () => {
        expect(CONFIGURABLE_CODING_RULES).toMatch(/Never assign a hardcoded default value to a configurable/);
        expect(CONFIGURABLE_CODING_RULES).toMatch(/`configurable string refreshUrl = "<provider-token-endpoint-url>";`/);
        expect(CONFIGURABLE_CODING_RULES).toMatch(/otherwise `configurable string refreshUrl = \?;`/);
        expect(CONFIGURABLE_CODING_RULES).toMatch(/`refreshUrl: refreshUrl`/);
    });

    it('covers naming, implicit finality, and secrets', () => {
        expect(CONFIGURABLE_CODING_RULES).toMatch(/two-word camelCase name/);
        expect(CONFIGURABLE_CODING_RULES).toMatch(/never write `final configurable`/);
        expect(CONFIGURABLE_CODING_RULES).toMatch(/secrets \(passwords, tokens, client secrets\) must be configurables, never literals in code/);
    });

    it('contains no unresolved interpolations or stray backtick escapes', () => {
        expect(CONFIGURABLE_CODING_RULES).not.toMatch(/\$\{/);
        expect(CONFIGURABLE_CODING_RULES).not.toMatch(/\\`/);
    });
});

describe('system prompt wiring', () => {
    it('imports the rules and interpolates them between Code Structure and Coding Rules', () => {
        expect(promptsSource).toMatch(/import \{ CONFIGURABLE_CODING_RULES \} from "\.\/configurable-rules";/);
        const codeStructure = promptsSource.indexOf('## Code Structure');
        const interpolation = promptsSource.indexOf('${CONFIGURABLE_CODING_RULES}');
        const codingRules = promptsSource.indexOf('## Coding Rules');
        expect(interpolation).toBeGreaterThan(codeStructure);
        expect(interpolation).toBeLessThan(codingRules);
    });

    it('replaces the old inline type list and refreshUrl section rather than duplicating them', () => {
        expect(promptsSource).not.toMatch(/Use only string, int, decimal, boolean types in configurable variables/);
        expect(promptsSource).not.toMatch(/## OAuth refreshUrl configurable/);
        expect(promptsSource).toMatch(/following the "Configurable variables" rules below/);
    });
});
