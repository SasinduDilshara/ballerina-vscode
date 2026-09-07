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
 * System-prompt rule scoping closed records to schemas the generated code owns.
 *
 * The Coding Rules ask for records instead of maps or json, and the langlib guide's conversion examples
 * used closed records, so an S3 event notification consumed from SQS was bound to `record {| ... |}` types
 * that declared only the fields the code used. Conversion then failed at run time on the first real event:
 * "field 'Records[0].eventVersion' cannot be added to the closed record". Nothing distinguished a schema
 * the code owns from one an external system produces, and the compiler cannot report the mismatch.
 *
 * Kept in its own module (no imports) so it can be unit-tested without loading the extension host.
 */
export const EXTERNAL_PAYLOAD_RECORD_RULE =
    "Records that bind data produced by an EXTERNAL system (cloud event notifications such as S3/SQS/SNS "
    + "events, webhooks, third-party API responses, messages consumed from queues or topics) MUST be OPEN "
    + "records (`record { ... }` — an open record implicitly accepts extra `anydata` fields): declare only the "
    + "fields the code uses and mark any field that may be absent optional (`string eventVersion?;`). Use CLOSED "
    + "records (`record {| ... |}`) only for schemas this code owns: internal models, log entries, and the "
    + "request/response contracts it defines. Binding a value that carries an undeclared field into a closed "
    + "record FAILS AT RUNTIME with `{ballerina/lang.value}ConversionError` (\"field 'x' cannot be added to the "
    + "closed record 'y'\") and is never reported by the compiler, so never bind an external payload into a "
    + "closed record with `cloneWithType()`, `fromJsonWithType()`, `fromJsonStringWithType()`, `ensureType()` "
    + "or HTTP/queue payload data binding.";
