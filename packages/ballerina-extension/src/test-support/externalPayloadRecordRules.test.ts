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
 * Guards the open-versus-closed record guidance. The regression that motivated it was silent: closed
 * records for an S3 event payload compiled cleanly and failed on the first real event, so the rule text
 * and the langlib examples are pinned here. `prompts.ts` cannot be imported (extension-host graph), so its
 * wiring is checked against the source text.
 */

import * as fs from 'fs';
import * as path from 'path';
import { EXTERNAL_PAYLOAD_RECORD_RULE } from '../features/ai/agent/external-payload-records';
import { LANGLIB_USAGE_INSTRUCTIONS } from '../features/ai/utils/libs/langlibs';

const promptsSource = fs.readFileSync(path.join(__dirname, '../features/ai/agent/prompts.ts'), 'utf-8');

describe('EXTERNAL_PAYLOAD_RECORD_RULE content', () => {
    it('scopes open records to external payloads and closed records to owned schemas', () => {
        expect(EXTERNAL_PAYLOAD_RECORD_RULE).toMatch(/EXTERNAL system/);
        expect(EXTERNAL_PAYLOAD_RECORD_RULE).toMatch(/MUST be OPEN records \(`record \{ \.\.\. \}`/);
        expect(EXTERNAL_PAYLOAD_RECORD_RULE).toMatch(/Use CLOSED records \(`record \{\| \.\.\. \|\}`\) only for schemas this code owns/);
    });

    it('names the runtime failure and every conversion path that triggers it', () => {
        expect(EXTERNAL_PAYLOAD_RECORD_RULE).toMatch(/FAILS AT RUNTIME/);
        expect(EXTERNAL_PAYLOAD_RECORD_RULE).toMatch(/\{ballerina\/lang\.value\}ConversionError/);
        expect(EXTERNAL_PAYLOAD_RECORD_RULE).toMatch(/never reported by the compiler/);
        for (const fn of ['cloneWithType()', 'fromJsonWithType()', 'fromJsonStringWithType()', 'ensureType()']) {
            expect(EXTERNAL_PAYLOAD_RECORD_RULE).toContain(fn);
        }
    });

    it('tells the model to mark possibly-absent fields optional', () => {
        expect(EXTERNAL_PAYLOAD_RECORD_RULE).toMatch(/mark any field that may be absent optional/);
    });

    it('contains no unresolved interpolation', () => {
        expect(EXTERNAL_PAYLOAD_RECORD_RULE).not.toMatch(/\$\{/);
    });
});

describe('system prompt wiring', () => {
    it('imports the rule and places it directly under the records-first coding rule', () => {
        expect(promptsSource).toMatch(/import \{ EXTERNAL_PAYLOAD_RECORD_RULE \} from "\.\/external-payload-records";/);
        const codingRules = promptsSource.indexOf('## Coding Rules');
        const recordsRule = promptsSource.indexOf('Use records as canonical representations', codingRules);
        const scopingRule = promptsSource.indexOf('${EXTERNAL_PAYLOAD_RECORD_RULE}', recordsRule);
        const fileMods = promptsSource.indexOf('## File modifications');
        expect(codingRules).toBeGreaterThan(-1);
        expect(scopingRule).toBeGreaterThan(recordsRule);
        expect(scopingRule).toBeLessThan(fileMods);
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
