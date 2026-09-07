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
 * System-prompt rules for declaring `configurable` variables.
 *
 * The configuration collector that WSO2 Integrator uses to fill Config.toml supports scalar values only
 * (string, int, decimal, boolean). A configurable declared with any other type — an `int:Signed32` port, a
 * `string[]` of OAuth scopes, an enum, a `byte` — is written to Config.toml as a scalar the runtime rejects
 * at startup ("configurable variable 'scopes' is expected to be of type 'string[] & readonly', but found
 * 'string'"). Copilot followed a library API's parameter type into the configurable declaration because the
 * prompt only listed the allowed types; it never said what to do when the API needs something else.
 *
 * Every conversion idiom below was compiled and run on Ballerina 2201.13.4, including the out-of-range case
 * (which surfaces as a returned error, not a panic). Kept in its own module (no imports) so it can be
 * unit-tested without loading the extension host.
 */
export const CONFIGURABLE_CODING_RULES = `## Configurable variables
- Define a \`configurable\` variable for every value that must come from the environment: hosts, ports, credentials, tokens, queue and topic names, bucket names, file paths, timeouts, feature flags. Never hardcode such values.
- A configurable variable may ONLY be declared with one of these types: \`string\`, \`int\`, \`decimal\`, \`boolean\`. The configuration collector cannot write any other type into Config.toml — not \`int:Signed32\`/\`int:Unsigned8\`/\`byte\`, not \`float\`, not arrays (\`string[]\`), not enums, not records, maps, unions or optional (\`string?\`) types. A configurable of any other type is written as a scalar and the program fails at startup with "configurable variable 'x' is expected to be of type '...' but found 'string'".
- When a library API needs a value of another type, STILL declare the configurable with the nearest allowed type and convert it at the point of use with a langlib call. Do NOT change the configurable's type to match the API. Verified patterns:
  - Integer subtypes: \`configurable int maxRetries = ?;\` then \`int:Signed32 retries = check maxRetries.ensureType();\` (same for \`int:Unsigned8\`, \`int:Signed16\`, \`byte\`: \`byte size = check bufferSize.ensureType();\`). \`ensureType\` returns an error, not a panic, when the value is out of range.
  - \`float\` from \`decimal\`: \`configurable decimal timeoutSeconds = ?;\` then \`float timeout = <float>timeoutSeconds;\`.
  - Enums and string-constant unions: \`configurable string logLevel = ?;\` then \`LogLevel level = check logLevel.ensureType();\`.
  - Lists: declare ONE comma-separated \`string\` and split it: \`configurable string oauthScopes = ?;\` then \`string[] scopes = from string scope in re \`,\`.split(oauthScopes) let string trimmed = scope.trim() where trimmed.length() > 0 select trimmed;\`. Never declare \`configurable string[]\` and never build a colon- or space-delimited string where the API takes \`string[]\` — split into the array.
  - Records and maps: declare one configurable per field the code needs and assemble the record in code.
  - Do the conversion once, in a module-level \`final\` variable or at the start of \`init()\`/the function that needs it, so a bad value is reported once at startup rather than on every request.
- Never assign a hardcoded default value to a configurable (\`configurable string host = ?;\`, not \`= "localhost"\`). The one exception: an OAuth2 refresh-token \`refreshUrl\` MAY carry the provider's token endpoint as its default when the library's type definition supplies one (\`configurable string refreshUrl = "<provider-token-endpoint-url>";\`); otherwise \`configurable string refreshUrl = ?;\`. Always reference it in the auth config (\`refreshUrl: refreshUrl\`).
- Use one configurable per value and a two-word camelCase name that says what it configures (\`smtpHost\`, \`imapPollingIntervalSeconds\`, \`disruptionsQueueName\`); put the unit in the name for durations and sizes.
- \`configurable\` variables are implicitly \`final\`: never write \`final configurable\`, and read them directly from any function or service (no \`lock\` needed).
- Providing values is a runtime task done through the configuration collector, never by editing Config.toml yourself; secrets (passwords, tokens, client secrets) must be configurables, never literals in code.`;
