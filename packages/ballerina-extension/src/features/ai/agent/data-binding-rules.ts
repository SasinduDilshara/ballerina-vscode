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
 * System-prompt rules for binding external data into records and for type casts.
 *
 * Open-versus-closed records: an S3 event notification consumed from SQS was bound to closed
 * `record {| ... |}` types and failed at run time on the first real event ("field 'Records[0].eventVersion'
 * cannot be added to the closed record"); the compiler cannot report the mismatch, so the rule scopes closed
 * records to schemas the generated code owns.
 *
 * Type casts: generated code cast values without checking what they were (`<map<json>>jwt:Payload`,
 * `<T>jsonValue`). A cast checks the value's inherent type, not its contents, so these compile and panic with
 * `{ballerina}TypeCastError`. An open record's rest field is `anydata`, so no open record value is a
 * `map<json>`, while a closed record whose fields are json-compatible is one. Every statement was run on
 * Ballerina 2201.13.4.
 *
 * Kept in its own module (no imports) so it can be unit-tested without loading the extension host.
 * Interpolated into the system prompt by getSystemPrompt() in ./prompts.ts.
 */
export const DATA_BINDING_CODING_RULES = `## Data binding, type casts and narrowing
- Records that bind data from an EXTERNAL system (cloud event notifications such as S3/SQS/SNS events, webhooks, third-party API responses, queue or topic messages) MUST be OPEN records (\`record { ... }\` — an open record implicitly accepts extra \`anydata\` fields): declare only the fields the code uses and mark any field that may be absent optional (\`string eventVersion?;\`). Use CLOSED records (\`record {| ... |}\`) only for schemas this code owns: internal models, log entries, and the request/response contracts it defines. Binding a value that carries an undeclared field into a closed record FAILS AT RUNTIME with \`{ballerina/lang.value}ConversionError\` ("field 'x' cannot be added to the closed record 'y'") and is never reported by the compiler, so never bind an external payload into a closed record with \`cloneWithType()\`, \`fromJsonWithType()\`, \`fromJsonStringWithType()\`, \`ensureType()\` or HTTP/queue payload data binding.
- A cast \`<T>x\` does not look at a value's contents. It succeeds only when the value already belongs to \`T\`; otherwise it panics at run time with \`{ballerina}TypeCastError\`. The compiler accepts a cast whenever the two types overlap, so a clean compile does not mean the cast will work.
- Whether a record can be cast to \`map<json>\` depends on how the record TYPE is declared, not on the values in it. A CLOSED record (\`record {| ... |}\`) whose fields are all json-compatible types (string, int, float, decimal, boolean, nil, json, arrays and other such closed records) IS a \`map<json>\`: \`<map<json>>closedValue\` is safe. An OPEN record (\`record { ... }\`, no bars) may hold extra fields of type \`anydata\`, so it is NOT a \`map<json>\` even when every field in it happens to be JSON: \`<map<json>>openValue\` compiles and panics. Library records such as \`jwt:Payload\` and connector response records are open. To get a \`map<json>\` from an open record, convert instead of casting: \`map<json> m = check payload.cloneWithType();\` or \`json j = payload.toJson();\`. If you only need a map view, \`map<anydata>\` accepts any record.
- Do not guess a type and cast to it. Check first with \`if x is T { ... }\` (inside the block \`x\` is a \`T\`), or convert with \`T v = check x.ensureType();\`; both return an error instead of panicking. To turn \`json\` into a record use \`T v = check x.cloneWithType();\` — never \`<T>jsonValue\`.
- Never cast to reach a field. To read a field the record type does not declare, or a field whose name is only known at run time, use member access on the record and check the result: \`anydata claim = payload[claimName]; if claim is string { ... }\`. Member access gives \`()\` when the field is absent; \`payload.get(name)\` panics when it is absent.
- On a \`json\` value use \`check j.name\` (or \`check j?.name\` when the field may be absent) and assign the result to a typed variable; do not chain casts on it.
- Numeric casts convert rather than check: \`<int>2.6\` gives 3, and casting an \`int\` that is out of range to \`byte\` or \`int:Signed32\` panics. Where a bad number must become an error, use \`check n.ensureType()\`.`;
