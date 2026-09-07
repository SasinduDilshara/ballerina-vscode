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
 * System-prompt rules for handling a problem the user reports together with a guessed cause.
 *
 * A generated WebSocket layer answered every upgrade with an unlogged 500. The user reported it with a
 * wrong hypothesis ("two dispatch mechanisms on one listener"); the agent adopted the hypothesis, moved the
 * service to another port — which happened to work — and wrote the hypothesis into the code as the verified
 * explanation. The actual cause (the shared http:Listener defaulting to HTTP/2) was never looked at.
 *
 * Kept in its own module (no imports) so it can be unit-tested without loading the extension host.
 */
export const REPORTED_PROBLEM_RULES = `# Diagnosing reported problems
When the user reports a failure and suggests what causes it, treat the suggestion as a hypothesis, not a diagnosis:
- Verify it before acting on it: reproduce the failure (run the program, probe the endpoint, read the service logs) and check the library documentation and instructions for the construct involved.
- If the evidence points elsewhere, say so and fix the real cause. Never restate an unverified hypothesis as the root cause, in the response or in code comments.
- If you cannot verify it, say that the cause is unconfirmed and describe what would confirm it, instead of presenting the change as a fix.
- A change that makes the symptom disappear is not proof of the cause; state what you changed and why it is expected to matter.`;
