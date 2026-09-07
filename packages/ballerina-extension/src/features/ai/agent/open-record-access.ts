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
 * System-prompt rule for reading a record field the record type does not declare, or whose name is only
 * known at run time.
 *
 * The Coding Rules say "navigate using the record fields" and "NEVER access json — define a record and
 * convert". For a custom JWT claim whose name came from a configurable there was no field to navigate and
 * no legal move left, so the agent manufactured a map view: `<map<json>>jwt:Payload`. That cast compiles and
 * panics at run time — an open record's implicit rest field is `anydata`, which is not `json` — and inside a
 * WebSocket upgrade resource the panic surfaced as HTTP 500 on every connection.
 *
 * Kept in its own module (no imports) so it can be unit-tested without loading the extension host.
 */
export const OPEN_RECORD_ACCESS_RULE =
    "A record value may carry fields its type does not declare (an open record such as `jwt:Payload`, or any "
    + "record bound from an external payload). To read such a field, or a field whose name is only known at run "
    + "time, use member access on the RECORD itself and narrow the result: `anydata claimValue = payload[claimName];` "
    + "then `if claimValue is string { ... }`. Member access returns `()` when the field is absent. This is the one "
    + "exception to the rule above: member access on a record is not json manipulation. Do NOT convert the record to "
    + "a map first — `<map<json>>payload` compiles but PANICS at run time with `{ballerina}TypeCastError`, because "
    + "an open record's implicit rest field type is `anydata`, not `json`. Do NOT use `.get(name)` for a field that "
    + "may be absent — it panics with `{ballerina/lang.map}KeyNotFound`; guard it with `.hasKey(name)` if you must "
    + "use it.";
