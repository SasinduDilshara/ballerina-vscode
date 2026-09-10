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
 * Pins the data-binding rules: open-versus-closed records for external payloads (the regression that
 * motivated it was silent — closed records for an S3 event payload compiled cleanly and failed on the first
 * real event) and the type-cast rules (run on Ballerina 2201.13.4). Also pins the langlib conversion
 * examples. `prompts.ts` cannot be imported here (extension-host module graph), so wiring is checked in
 * the source.
 */

import * as fs from 'fs';
import * as path from 'path';
import { DATA_BINDING_CODING_RULES } from '../features/ai/agent/data-binding-rules';
import { LANGLIB_USAGE_INSTRUCTIONS } from '../features/ai/utils/libs/langlibs';

const promptsSource = fs.readFileSync(path.join(__dirname, '../features/ai/agent/prompts.ts'), 'utf-8');

describe('DATA_BINDING_CODING_RULES content', () => {
    it('is a markdown section with the expected heading', () => {
        expect(DATA_BINDING_CODING_RULES.startsWith('## Data binding, type casts and narrowing')).toBe(true);
    });

    it('scopes open records to external payloads and closed records to owned schemas', () => {
        expect(DATA_BINDING_CODING_RULES).toMatch(/EXTERNAL system/);
        expect(DATA_BINDING_CODING_RULES).toMatch(/MUST be OPEN records \(`record \{ \.\.\. \}`/);
        expect(DATA_BINDING_CODING_RULES).toMatch(/Use CLOSED records \(`record \{\| \.\.\. \|\}`\) only for schemas this code owns/);
    });

    it('names the runtime failure and every conversion path that triggers it', () => {
        expect(DATA_BINDING_CODING_RULES).toMatch(/FAILS AT RUNTIME/);
        expect(DATA_BINDING_CODING_RULES).toMatch(/\{ballerina\/lang\.value\}ConversionError/);
        expect(DATA_BINDING_CODING_RULES).toMatch(/never reported by the compiler/);
        for (const fn of ['cloneWithType()', 'fromJsonWithType()', 'fromJsonStringWithType()', 'ensureType()']) {
            expect(DATA_BINDING_CODING_RULES).toContain(fn);
        }
    });

    it('tells the model to mark possibly-absent fields optional', () => {
        expect(DATA_BINDING_CODING_RULES).toMatch(/mark any field that may be absent optional/);
    });

    it('states that a cast checks membership, not contents, and that compiling proves nothing', () => {
        expect(DATA_BINDING_CODING_RULES).toMatch(/does not look at a value's contents/);
        expect(DATA_BINDING_CODING_RULES).toMatch(/panics at run time with `\{ballerina\}TypeCastError`/);
        expect(DATA_BINDING_CODING_RULES).toMatch(/a clean compile does not mean the cast will work/);
    });

    it('distinguishes closed records (castable to map<json>) from open records (not castable)', () => {
        expect(DATA_BINDING_CODING_RULES).toMatch(/depends on how the record TYPE is declared, not on the values in it/);
        expect(DATA_BINDING_CODING_RULES).toMatch(/A CLOSED record \(`record \{\| \.\.\. \|\}`\) whose fields are all json-compatible types .* IS a `map<json>`: `<map<json>>closedValue` is safe/);
        expect(DATA_BINDING_CODING_RULES).toMatch(/An OPEN record \(`record \{ \.\.\. \}`, no bars\) may hold extra fields of type `anydata`/);
        expect(DATA_BINDING_CODING_RULES).toMatch(/`<map<json>>openValue` compiles and panics/);
        expect(DATA_BINDING_CODING_RULES).toMatch(/`jwt:Payload` and connector response records are open/);
    });

    it('offers conversion, not casting, to get map<json> from an open record', () => {
        expect(DATA_BINDING_CODING_RULES).toContain('`map<json> m = check payload.cloneWithType();`');
        expect(DATA_BINDING_CODING_RULES).toContain('`json j = payload.toJson();`');
        expect(DATA_BINDING_CODING_RULES).toMatch(/`map<anydata>` accepts any record/);
    });

    it('teaches is-narrowing, ensureType and cloneWithType over guessing casts', () => {
        expect(DATA_BINDING_CODING_RULES).toMatch(/Do not guess a type and cast to it/);
        expect(DATA_BINDING_CODING_RULES).toContain('`if x is T { ... }`');
        expect(DATA_BINDING_CODING_RULES).toContain('`T v = check x.ensureType();`');
        expect(DATA_BINDING_CODING_RULES).toContain('`T v = check x.cloneWithType();` — never `<T>jsonValue`');
    });

    it('teaches member access for undeclared fields and warns about .get()', () => {
        expect(DATA_BINDING_CODING_RULES).toMatch(/Never cast to reach a field/);
        expect(DATA_BINDING_CODING_RULES).toContain('`anydata claim = payload[claimName]; if claim is string { ... }`');
        expect(DATA_BINDING_CODING_RULES).toMatch(/Member access gives `\(\)` when the field is absent; `payload\.get\(name\)` panics when it is absent/);
    });

    it('covers json field access and numeric casts', () => {
        expect(DATA_BINDING_CODING_RULES).toMatch(/`check j\.name` \(or `check j\?\.name` when the field may be absent\)/);
        expect(DATA_BINDING_CODING_RULES).toMatch(/`<int>2\.6` gives 3/);
        expect(DATA_BINDING_CODING_RULES).toMatch(/out of range to `byte` or `int:Signed32` panics/);
        expect(DATA_BINDING_CODING_RULES).toContain('`check n.ensureType()`');
    });

    it('contains no unresolved interpolations or stray backtick escapes', () => {
        expect(DATA_BINDING_CODING_RULES).not.toMatch(/\$\{/);
        expect(DATA_BINDING_CODING_RULES).not.toMatch(/\\`/);
    });
});

describe('system prompt wiring', () => {
    it('imports the rules and places them inside the Coding Rules region, after the records-first rule', () => {
        expect(promptsSource).toMatch(/import \{ DATA_BINDING_CODING_RULES \} from "\.\/data-binding-rules";/);
        const codingRules = promptsSource.indexOf('## Coding Rules');
        const recordsRule = promptsSource.indexOf('Use records as canonical representations', codingRules);
        const interpolation = promptsSource.indexOf('${DATA_BINDING_CODING_RULES}');
        const fileMods = promptsSource.indexOf('## File modifications');
        expect(codingRules).toBeGreaterThan(-1);
        expect(interpolation).toBeGreaterThan(recordsRule);
        expect(interpolation).toBeLessThan(fileMods);
        expect(promptsSource.split('${DATA_BINDING_CODING_RULES}')).toHaveLength(2);
    });

    it('amends the no-json-access rule with the record member-access exception so the two do not conflict', () => {
        expect(promptsSource).toMatch(/NEVER access or manipulate Json variables\..*Member access on a RECORD \(see "Data binding, type casts and narrowing"\) is not json manipulation and is the one allowed way to read an undeclared field\./);
    });

    it('the absorbed payload and type-cast rules are no longer wired separately', () => {
        expect(promptsSource).not.toContain('EXTERNAL_PAYLOAD_RECORD_RULE');
        expect(promptsSource).not.toContain('TYPE_CAST_CODING_RULES');
    });
});

describe('langlib conversion examples', () => {
    it('convert into OPEN records, never into a closed record', () => {
        // Every `check ... WithType(...)` target record declared in the guide must be open.
        const conversions = [...LANGLIB_USAGE_INSTRUCTIONS.matchAll(/type (\w+) record \{\|[^}]*\|\};\n(\w+) \w+ = check [^\n]*WithType/g)];
        expect(conversions).toHaveLength(0);
        expect(LANGLIB_USAGE_INSTRUCTIONS).toContain('type Config record { int port; int timeout; };');
        expect(LANGLIB_USAGE_INSTRUCTIONS).toContain('type Config record { int port; int timeout = 60; };');
    });

    it('explain the open-versus-closed distinction with a labelled example of each', () => {
        expect(LANGLIB_USAGE_INSTRUCTIONS).toMatch(/Open versus closed records in conversions/);
        expect(LANGLIB_USAGE_INSTRUCTIONS).toMatch(/\{ballerina\/lang\.value\}ConversionError/);
        expect(LANGLIB_USAGE_INSTRUCTIONS).toMatch(/External payload: open/);
        expect(LANGLIB_USAGE_INSTRUCTIONS).toMatch(/Owned schema: closed/);
        expect(LANGLIB_USAGE_INSTRUCTIONS).toMatch(/type S3EventRecord record \{\n/);
        expect(LANGLIB_USAGE_INSTRUCTIONS).toMatch(/type S3EventLogEntry record \{\|/);
    });

    it('keep the only remaining closed record in the guide as the labelled owned-schema example', () => {
        const closed = [...LANGLIB_USAGE_INSTRUCTIONS.matchAll(/type (\w+) record \{\|/g)].map((m) => m[1]);
        expect(closed).toEqual(['S3EventLogEntry']);
    });
});
