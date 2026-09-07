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
 * System-prompt rule on what `clone()` does to an immutable value.
 *
 * Generated code made a request array `readonly` (via `cloneReadOnly()`) so it could enter a lock, then
 * stored `requested.clone()` as a subscriber's mutable queue list. The language spec (§5.9.2.1) defines
 * Clone(v) for an immutable v as v itself, so the stored list was still immutable, and the next message's
 * `push` panicked with `{ballerina/lang.array}InvalidUpdate` — an order-dependent crash that took the whole
 * server down. Nothing in the prompt said that cloning an immutable value does not produce a mutable copy.
 *
 * The remedies were run on Ballerina 2201.13.4: a query expression followed by `.clone()` yields a mutable
 * array in every position, including direct assignment to an `isolated` variable inside a lock (a bare query
 * expression there fails BCE3959); `check ro.cloneWithType()` also yields a mutable array but only where the
 * enclosing function can return `error`.
 *
 * Kept in its own module (no imports) so it can be unit-tested without loading the extension host.
 */
export const IMMUTABLE_CLONE_RULE =
    "`clone()` and `cloneReadOnly()` on a value that is ALREADY immutable return the SAME immutable value — they do "
    + "NOT make a mutable copy (the spec defines Clone(v) for an immutable v as v). So a value you made `readonly` "
    + "(a `readonly & T` parameter, a `cloneReadOnly()` result, a `readonly` record field) stays immutable after "
    + "`.clone()`, and storing it into a field or variable whose type is mutable makes a later `push`/member update "
    + "panic at run time with `{ballerina/lang.array}InvalidUpdate` (\"modification not allowed on readonly value\"). "
    + "Diagnostics cannot catch this. To obtain genuinely mutable state from a readonly value, REBUILD it: "
    + "`string[] fresh = (from string q in roQueues select q).clone();` — the trailing `.clone()` is what makes the "
    + "expression an isolated expression, so this form also works when assigning straight to an `isolated` variable "
    + "inside a lock (a bare query expression there is a compile error) — or, in a function that can return `error`, "
    + "`string[] fresh = check roQueues.cloneWithType();`. Never store a readonly-derived value where later code will "
    + "mutate it.";
