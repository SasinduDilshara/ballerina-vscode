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
 * System-prompt rule making the agent check that every requested behaviour has a code path before it
 * declares a task or a turn done.
 *
 * The only completion check the prompt asked for was a clean compile. A request for "when a ticket is
 * created or updated ... push the change to every connected agent" produced a broadcast wired into the PUT
 * handler only; the TICKET_CREATED enum member was declared and never referenced, the project compiled, and
 * the omission was found by the user. Diagnostics report errors only, so an unused symbol never reaches the
 * model either — the check has to be a rule.
 *
 * Interpolated at both completion points in ./prompts.ts: the Edit-mode validation step and the Plan-mode
 * per-task completion bullet. Kept in its own module (no imports) so it can be unit-tested in isolation.
 */
export const REQUIREMENT_COVERAGE_RULE =
    "Compilation is not evidence of completeness. Before you finish, check requirement coverage in your reasoning "
    + "(do NOT print the list): for every behaviour the user asked for — each endpoint, trigger, event, status and "
    + "BOTH sides of any \"when X or Y\" phrase — name the code path that implements it. When one behaviour must "
    + "fire from several operations (create/update/delete, connect/disconnect), wire it into EVERY handler, not "
    + "just one. A constant, enum member, type or function you declared but never referenced almost always means a "
    + "path was left unwired. Fix every gap before moving on.";
