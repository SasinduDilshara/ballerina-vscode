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
 * Pins the type-cast rules. The behaviours they state were run on Ballerina 2201.13.4; the assertions keep
 * the open/closed distinction and the safe alternatives from drifting. `prompts.ts` cannot be imported here
 * (extension-host module graph), so wiring is checked in the source.
 */

import * as fs from 'fs';
import * as path from 'path';
import { TYPE_CAST_CODING_RULES } from '../features/ai/agent/type-cast-rules';

const promptsSource = fs.readFileSync(path.join(__dirname, '../features/ai/agent/prompts.ts'), 'utf-8');

describe('TYPE_CAST_CODING_RULES content', () => {
    it('states that a cast checks membership, not contents, and that compiling proves nothing', () => {
        expect(TYPE_CAST_CODING_RULES.startsWith('## Type casts and narrowing')).toBe(true);
        expect(TYPE_CAST_CODING_RULES).toMatch(/does not look at a value's contents/);
        expect(TYPE_CAST_CODING_RULES).toMatch(/panics at run time with `\{ballerina\}TypeCastError`/);
        expect(TYPE_CAST_CODING_RULES).toMatch(/a clean compile does not mean the cast will work/);
    });

    it('distinguishes closed records (castable to map<json>) from open records (not castable)', () => {
        expect(TYPE_CAST_CODING_RULES).toMatch(/depends on how the record TYPE is declared, not on the values in it/);
        expect(TYPE_CAST_CODING_RULES).toMatch(/A CLOSED record \(`record \{\| \.\.\. \|\}`\) whose fields are all json-compatible types .* IS a `map<json>`: `<map<json>>closedValue` is safe/);
        expect(TYPE_CAST_CODING_RULES).toMatch(/An OPEN record \(`record \{ \.\.\. \}`, no bars\) may hold extra fields of type `anydata`/);
        expect(TYPE_CAST_CODING_RULES).toMatch(/`<map<json>>openValue` compiles and panics/);
        expect(TYPE_CAST_CODING_RULES).toMatch(/`jwt:Payload` and connector response records are open/);
    });

    it('offers conversion, not casting, to get map<json> from an open record', () => {
        expect(TYPE_CAST_CODING_RULES).toContain('`map<json> m = check payload.cloneWithType();`');
        expect(TYPE_CAST_CODING_RULES).toContain('`json j = payload.toJson();`');
        expect(TYPE_CAST_CODING_RULES).toMatch(/`map<anydata>` accepts any record/);
    });

    it('teaches is-narrowing, ensureType and cloneWithType over guessing casts', () => {
        expect(TYPE_CAST_CODING_RULES).toMatch(/Do not guess a type and cast to it/);
        expect(TYPE_CAST_CODING_RULES).toContain('`if x is T { ... }`');
        expect(TYPE_CAST_CODING_RULES).toContain('`T v = check x.ensureType();`');
        expect(TYPE_CAST_CODING_RULES).toContain('`T v = check x.cloneWithType();` — never `<T>jsonValue`');
    });

    it('teaches member access for undeclared fields and warns about .get()', () => {
        expect(TYPE_CAST_CODING_RULES).toMatch(/Never cast to reach a field/);
        expect(TYPE_CAST_CODING_RULES).toContain('`anydata claim = payload[claimName]; if claim is string { ... }`');
        expect(TYPE_CAST_CODING_RULES).toMatch(/Member access gives `\(\)` when the field is absent; `payload\.get\(name\)` panics when it is absent/);
    });

    it('covers json field access and numeric casts', () => {
        expect(TYPE_CAST_CODING_RULES).toMatch(/`check j\.name` \(or `check j\?\.name` when the field may be absent\)/);
        expect(TYPE_CAST_CODING_RULES).toMatch(/`<int>2\.6` gives 3/);
        expect(TYPE_CAST_CODING_RULES).toMatch(/out of range to `byte` or `int:Signed32` panics/);
        expect(TYPE_CAST_CODING_RULES).toContain('`check n.ensureType()`');
    });

    it('contains no unresolved interpolations or stray backtick escapes', () => {
        expect(TYPE_CAST_CODING_RULES).not.toMatch(/\$\{/);
        expect(TYPE_CAST_CODING_RULES).not.toMatch(/\\`/);
    });
});

describe('system prompt wiring', () => {
    it('imports the rules and places them inside the Coding Rules region', () => {
        expect(promptsSource).toMatch(/import \{ TYPE_CAST_CODING_RULES \} from "\.\/type-cast-rules";/);
        const codingRules = promptsSource.indexOf('## Coding Rules');
        const interpolation = promptsSource.indexOf('${TYPE_CAST_CODING_RULES}');
        const fileMods = promptsSource.indexOf('## File modifications');
        expect(interpolation).toBeGreaterThan(codingRules);
        expect(interpolation).toBeLessThan(fileMods);
    });

    it('amends the no-json-access rule with the record member-access exception so the two do not conflict', () => {
        expect(promptsSource).toMatch(/NEVER access or manipulate Json variables\..*Member access on a RECORD \(see "Type casts and narrowing"\) is not json manipulation and is the one allowed way to read an undeclared field\./);
    });
});
