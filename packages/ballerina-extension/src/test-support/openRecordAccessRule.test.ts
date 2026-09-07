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
 * Pins the open-record member-access guidance in the coding rules and the langlib guide. The regression it
 * guards compiled cleanly and panicked at run time, so the exact idiom and the exact prohibitions matter.
 */

import * as fs from 'fs';
import * as path from 'path';
import { OPEN_RECORD_ACCESS_RULE } from '../features/ai/agent/open-record-access';
import { LANGLIB_USAGE_INSTRUCTIONS } from '../features/ai/utils/libs/langlibs';

const promptsSource = fs.readFileSync(path.join(__dirname, '../features/ai/agent/prompts.ts'), 'utf-8');

describe('OPEN_RECORD_ACCESS_RULE content', () => {
    it('teaches member access on the record with narrowing, and that absent fields read as ()', () => {
        expect(OPEN_RECORD_ACCESS_RULE).toMatch(/`anydata claimValue = payload\[claimName\];`/);
        expect(OPEN_RECORD_ACCESS_RULE).toMatch(/`if claimValue is string \{ \.\.\. \}`/);
        expect(OPEN_RECORD_ACCESS_RULE).toMatch(/returns `\(\)` when the field is absent/);
    });

    it('forbids the map<json> cast with the correct reason', () => {
        expect(OPEN_RECORD_ACCESS_RULE).toMatch(/`<map<json>>payload` compiles but PANICS at run time with `\{ballerina\}TypeCastError`/);
        expect(OPEN_RECORD_ACCESS_RULE).toMatch(/implicit rest field type is `anydata`, not `json`/);
    });

    it('warns that .get() panics on an absent field and offers hasKey as the guard', () => {
        expect(OPEN_RECORD_ACCESS_RULE).toMatch(/`\.get\(name\)`.*panics with `\{ballerina\/lang\.map\}KeyNotFound`/);
        expect(OPEN_RECORD_ACCESS_RULE).toMatch(/`\.hasKey\(name\)`/);
    });

    it('is framed as the exception to the no-json-access rule', () => {
        expect(OPEN_RECORD_ACCESS_RULE).toMatch(/the one exception to the rule above/);
    });

    it('does not wrongly ban the map<anydata> cast, which is legal', () => {
        expect(OPEN_RECORD_ACCESS_RULE).not.toMatch(/map<anydata>/);
    });

    it('contains no unresolved interpolation', () => {
        expect(OPEN_RECORD_ACCESS_RULE).not.toMatch(/\$\{/);
    });
});

describe('system prompt wiring', () => {
    it('imports the rule and places it directly after the no-json-access coding rule', () => {
        expect(promptsSource).toMatch(/import \{ OPEN_RECORD_ACCESS_RULE \} from "\.\/open-record-access";/);
        const jsonRule = promptsSource.indexOf('NEVER access or manipulate Json variables');
        const interpolation = promptsSource.indexOf('${OPEN_RECORD_ACCESS_RULE}');
        const fileMods = promptsSource.indexOf('## File modifications');
        expect(jsonRule).toBeGreaterThan(-1);
        expect(interpolation).toBeGreaterThan(jsonRule);
        expect(interpolation).toBeLessThan(fileMods);
        // Directly after: no other bullet in between.
        expect(promptsSource.slice(jsonRule, interpolation).split('\n- ')).toHaveLength(2);
    });
});

describe('langlib guide', () => {
    it('has a section on undeclared or dynamically named record fields with the member-access idiom', () => {
        expect(LANGLIB_USAGE_INSTRUCTIONS).toMatch(/## Reading undeclared or dynamically named record fields/);
        expect(LANGLIB_USAGE_INSTRUCTIONS).toMatch(/anydata claimValue = payload\[claimName\]; \/\/ \(\) if the field is absent/);
        expect(LANGLIB_USAGE_INSTRUCTIONS).toMatch(/if claimValue is string \{/);
    });

    it('names both panics and offers toJson() for a json view', () => {
        expect(LANGLIB_USAGE_INSTRUCTIONS).toMatch(/`<map<json>>payload` compiles but panics at runtime with `\{ballerina\}TypeCastError`/);
        expect(LANGLIB_USAGE_INSTRUCTIONS).toMatch(/`\{ballerina\/lang\.map\}KeyNotFound`/);
        expect(LANGLIB_USAGE_INSTRUCTIONS).toMatch(/json j = payload\.toJson\(\);/);
    });
});
