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
 * The system prompt had no guidance on trying endpoints out at all, which is how a WebSocket service came to
 * be "verified" with an HTTP client and its timeouts read as success. `prompts.ts` cannot be imported here
 * (extension-host module graph), so the block and its tool-name interpolations are checked in the source.
 */

import * as fs from 'fs';
import * as path from 'path';

const promptsSource = fs.readFileSync(path.join(__dirname, '../features/ai/agent/prompts.ts'), 'utf-8');

describe('endpoint verification guidance in the system prompt', () => {
    const block = promptsSource.slice(promptsSource.indexOf('## Trying out endpoints'), promptsSource.indexOf('## Test Runner'));

    it('exists inside the "Running, invoking and tests" section', () => {
        const section = promptsSource.indexOf('# Running, invoking and tests');
        const heading = promptsSource.indexOf('## Trying out endpoints');
        const testRunner = promptsSource.indexOf('## Test Runner');
        expect(section).toBeGreaterThan(-1);
        expect(heading).toBeGreaterThan(section);
        expect(heading).toBeLessThan(testRunner);
    });

    it('scopes Hurl to HTTP and forbids reading a timeout as a successful upgrade', () => {
        expect(block).toMatch(/\$\{HURL_TOOL_NAME\} for HTTP endpoints ONLY/);
        expect(block).toMatch(/Hurl cannot open WebSocket connections/);
        expect(block).toMatch(/never read a timeout or a missing HTTP 101 as a successful upgrade/);
    });

    it('points at the probe tool and states what a failed probe means', () => {
        expect(block).toMatch(/\$\{WEBSOCKET_PROBE_TOOL_NAME\} to verify a WebSocket service/);
        expect(block).toMatch(/400 = returned websocket:UpgradeError, 401\/403 = auth, 500 = a panic in the upgrade resource or an incompatible http:Listener/);
        expect(block).toMatch(/zero received frames from a service that is expected to push data means the service is NOT working/);
        expect(block).toMatch(/\$\{BALLERINA_GET_LOGS_TOOL_NAME\}/);
    });

    it('requires every requested trigger to be exercised when the user asked to run or try out', () => {
        expect(block).toMatch(/exercise EACH requested trigger at least once/);
        expect(block).toMatch(/report every trigger you could not exercise as unverified/);
    });

    it('imports every tool name it interpolates', () => {
        expect(promptsSource).toMatch(/import \{ HURL_TOOL_NAME \} from "\.\/tools\/hurl-tool";/);
        expect(promptsSource).toMatch(/import \{ WEBSOCKET_PROBE_TOOL_NAME \} from "\.\/tools\/websocket-probe";/);
        expect(promptsSource).toMatch(/import \{ BALLERINA_GET_LOGS_TOOL_NAME \} from "\.\/tools\/ballerina-get-logs";/);
    });
});
