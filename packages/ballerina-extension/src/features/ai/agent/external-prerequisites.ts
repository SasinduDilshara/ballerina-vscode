// Copyright (c) 2026, WSO2 LLC. (https://www.wso2.com/) All Rights Reserved.

// WSO2 LLC. licenses this file to you under the Apache License,
// Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.
// You may obtain a copy of the License at

// http://www.apache.org/licenses/LICENSE-2.0

// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.

/**
 * System-prompt rule making the agent state the external resources the generated code assumes but
 * does not create.
 *
 * Generated messaging consumers bound a Solace queue that the connector cannot create (the library README
 * says so), and every consumer failed at bind on a clean broker with no warning anywhere in the response:
 * the summary rule asks for a very concise summary, and nothing told the agent that a README's
 * "create the queue first" is a prerequisite worth repeating. Interpolated into both the Edit-mode summary
 * step and the Plan-mode user-communication rules in ./prompts.ts.
 *
 * Kept in its own module (no imports) so it can be unit-tested without loading the extension host.
 */
export const EXTERNAL_PREREQUISITES_RULE =
    "If the code binds external resources it does not create (message broker queues, topics, endpoints or "
    + "dead-letter queues; database tables; storage buckets; webhooks; API credentials), end the response with a "
    + "short **Prerequisites** list naming only those that actually appear in the code or its configurables and "
    + "must exist before it runs. Take them from the library's README and instructions; never invent setup steps "
    + "they do not state.";
