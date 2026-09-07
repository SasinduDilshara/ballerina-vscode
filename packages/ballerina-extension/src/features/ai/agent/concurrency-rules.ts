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
 * System-prompt rules for writing code that satisfies the compiler's isolation analysis up front:
 * which module-level variables an `isolated` function or a service method may read, when a variable
 * must be `isolated` and guarded by `lock`, and how isolated objects fit in.
 *
 * The rule that matters most is the one the language spec states in §7.3.5 (Isolated functions): a
 * module-level variable may be read from an isolated context without a lock when it is `final` (or
 * `configurable`, or a listener/service) AND its static type is a subtype of `readonly` OR of
 * `isolated object {}`. Guidance that mentioned only the `readonly` half made the agent conclude that a
 * `final` instance of an `isolated class` (a store, an `ai:Agent`, a connector client) could not be used
 * from a service, and it then wrapped the object in an `isolated` variable plus a `lock`, or added
 * `& readonly` to a type that cannot be immutable.
 *
 * Kept in its own module (no imports) so it can be unit-tested without loading the extension host.
 * Interpolated into the system prompt by getSystemPrompt() in ./prompts.ts.
 */
export const CONCURRENCY_CODING_RULES = `## Concurrency Safety and Shared State
Ballerina checks isolation at compile time so services can handle requests concurrently. Follow these rules whenever the code has module-level state, service fields, or workers:
- \`configurable\` variables are implicitly final and readable from any isolated context — NEVER write \`final configurable\` (it is a compile error).
- For other module-level variables: if the variable is never reassigned, declare it \`final\`. A \`final\` module-level variable can be read from an \`isolated\` function or a service method WITHOUT a lock when its static type is immutable (\`readonly\`, e.g. \`final int[] & readonly defaults = [1, 2];\`) OR an isolated object — that covers most connector clients and listeners (e.g. \`final http:Client httpClient = check new (url);\`), \`ai:Agent\`, and instances of a class you declared \`isolated class\`. Do NOT declare such a variable \`isolated\`, do NOT wrap its use in \`lock { }\`, and do NOT add \`& readonly\` to it. \`final\` alone is not sufficient for any OTHER type: a \`final map<string>\` or \`final int[]\` is still non-isolated mutable state. If the state is shared and MUTABLE, declare the variable \`isolated\` (e.g. \`isolated int[] requests = [];\`) and access it ONLY inside \`lock { }\` blocks; an \`isolated\` variable must be initialized at its declaration.
- When you write your own class that will be held in a module-level \`final\` variable and used from services, declare it \`isolated class\`, declare every mutable field \`private\`, initialize each field with a fresh literal or constructor, and access \`self\` fields only inside \`lock { }\` blocks within its methods.
- In a service holding mutable state, apply the same rules to its fields (\`private\`, fresh initializer, \`self\` only inside \`lock { }\`). For requests to be dispatched concurrently, each resource/remote method must ALSO satisfy isolated-function rules: it may only call \`isolated\` functions and must not touch non-final module-level mutable state. The compiler then infers both the service and its methods as \`isolated\`.
- One \`lock\` block may protect only ONE isolated root: never access two \`isolated\` variables (or an \`isolated\` variable plus \`self\`) in the same lock. Use separate lock statements and pass values between them via local variables. If two pieces of state must change together atomically, store them in ONE protected value (a single record or map behind one isolated variable).
- A value that leaves a lock protecting an isolated root (returned or assigned to an outer variable) must not alias the protected state: copy it with \`.clone()\` (or \`.cloneReadOnly()\` when an immutable result is acceptable). Immutable (\`readonly\`) values and single isolated objects (a client, a caller) may leave without cloning. An ordinary lock over non-isolated state has no transfer restrictions — do not add gratuitous clones there.
- Never use \`start\`, named \`worker\` declarations, or worker send/receive actions (\`->\`/\`<-\`) inside a \`lock\` block. Read the needed values into locals inside the lock, then do the async work after it.
- Functions called from an \`isolated\` function, or from inside a lock protecting isolated state, must themselves be \`isolated\`. Write helper functions as \`isolated\` when they only work on their parameters and local state.
- Keep lock blocks minimal: only the shared-state read/write belongs inside; perform I/O and remote calls outside the lock.`;
