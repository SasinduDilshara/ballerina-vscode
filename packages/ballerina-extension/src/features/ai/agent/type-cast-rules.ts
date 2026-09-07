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
 * System-prompt rules on when a type cast is safe.
 *
 * Generated code cast values to a type without checking what they were: `<map<json>>jwt:Payload` to read a
 * custom claim, `<T>jsonValue` to bind a payload. A cast checks the value's inherent type, not its contents,
 * so these compile and panic at run time with `{ballerina}TypeCastError`; inside a WebSocket upgrade resource
 * the panic surfaced as HTTP 500 on every connection. The open/closed distinction is the part the agent got
 * wrong most often: an open record's rest field is `anydata`, so no open record value is a `map<json>`, while
 * a closed record whose fields are json-compatible is one.
 *
 * Every statement below was run on Ballerina 2201.13.4: the open-record cast panics and its `is map<json>`
 * test is false; the closed-record cast succeeds (the compiler reports its `is` test as always true);
 * `cloneWithType`/`toJson` convert an open record; `<int>2.6` yields 3; an out-of-range `<byte>` panics while
 * `ensureType` returns an error; member access on an absent field yields `()`.
 *
 * Kept in its own module (no imports) so it can be unit-tested without loading the extension host.
 */
export const TYPE_CAST_CODING_RULES = `## Type casts and narrowing
- A cast \`<T>x\` does not look at a value's contents. It succeeds only when the value already belongs to \`T\`; otherwise it panics at run time with \`{ballerina}TypeCastError\`. The compiler accepts a cast whenever the two types overlap, so a clean compile does not mean the cast will work.
- Whether a record can be cast to \`map<json>\` depends on how the record TYPE is declared, not on the values in it. A CLOSED record (\`record {| ... |}\`) whose fields are all json-compatible types (string, int, float, decimal, boolean, nil, json, arrays and other such closed records) IS a \`map<json>\`: \`<map<json>>closedValue\` is safe. An OPEN record (\`record { ... }\`, no bars) may hold extra fields of type \`anydata\`, so it is NOT a \`map<json>\` even when every field in it happens to be JSON: \`<map<json>>openValue\` compiles and panics. Library records such as \`jwt:Payload\` and connector response records are open. To get a \`map<json>\` from an open record, convert instead of casting: \`map<json> m = check payload.cloneWithType();\` or \`json j = payload.toJson();\`. If you only need a map view, \`map<anydata>\` accepts any record.
- Do not guess a type and cast to it. Check first with \`if x is T { ... }\` (inside the block \`x\` is a \`T\`), or convert with \`T v = check x.ensureType();\`. Both return an error instead of panicking. To turn \`json\` into a record use \`T v = check x.cloneWithType();\` — never \`<T>jsonValue\`.
- Never cast to reach a field. To read a field the record type does not declare, or a field whose name is only known at run time, use member access on the record and check the result: \`anydata claim = payload[claimName]; if claim is string { ... }\`. Member access gives \`()\` when the field is absent; \`payload.get(name)\` panics when it is absent.
- On a \`json\` value use \`check j.name\` (or \`check j?.name\` when the field may be absent) and assign the result to a typed variable; do not chain casts on it.
- Numeric casts convert rather than check: \`<int>2.6\` gives 3, and casting an \`int\` that is out of range to \`byte\` or \`int:Signed32\` panics. Where a bad number must become an error, use \`check n.ensureType()\`.`;
