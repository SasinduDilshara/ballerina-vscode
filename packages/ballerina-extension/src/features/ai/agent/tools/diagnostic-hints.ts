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
 * Map of Ballerina diagnostic codes to resolving hints.
 *
 * Each entry maps a diagnostic code (e.g., "BCE3943") to a directive hint on how to resolve it. Hints ride
 * along with the diagnostics returned by the getCompilationErrors tool so the agent can apply the canonical
 * fix instead of guessing at the compiler's isolation terminology. The isolation entries follow the language
 * spec §7.3.5 / §7.23 and the compiler's IsolationAnalyzer messages (quoted above each entry).
 *
 * Kept in its own module (no imports) so it can be unit-tested without loading the extension host.
 */
export const DIAGNOSTIC_HINTS: Readonly<Record<string, string>> = {
    "BCE2000": "This usually indicates a missing import statement. Please ensure that all necessary modules are imported in each file where they are used.",

    // "invalid access of mutable storage in an 'isolated' function"
    "BCE3943": "An `isolated` function may only read module-level state that is (a) `final` (or `configurable`) with a type "
        + "that is immutable (`readonly`) OR an isolated object (a connector client, a listener, `ai:Agent`, an instance of an "
        + "`isolated class`), or (b) declared `isolated` and accessed only inside `lock { }`. If the reported variable is already "
        + "`final` and its type is an isolated object, the access is legal — the error comes from a DIFFERENT variable in the same "
        + "function. Fix: if the variable is never reassigned, declare it `final` (and give it an immutable type if it is a "
        + "map/array/record); if it is shared mutable state, declare it `isolated` (e.g. `isolated int[] stack = [];`) and wrap "
        + "every access in `lock { }`. Do NOT simply drop the `isolated` qualifier from a resource/remote method — that disables "
        + "concurrent dispatch.",

    // "invalid non-private mutable field in an isolated object"
    "BCE3956": "Every mutable field of an isolated object/class must be `private`. Add the `private` qualifier, or make the "
        + "field `final` with an immutable (`readonly`) or isolated-object type if it is never reassigned.",

    // "invalid access of a mutable field of an 'isolated' object outside a 'lock' statement"
    "BCE3957": "Access to a mutable `self` field of an isolated object must be inside a `lock` statement. Wrap the statement(s) "
        + "in `lock { ... }` (group nearby accesses of the field into one lock; mutable values leaving the lock must be cloned).",

    // "invalid attempt to transfer out a value from a 'lock' statement with restricted variable usage: expected an isolated expression"
    "BCE3959": "A value leaving a lock that protects an isolated variable or `self` (via `return` or assignment to an outer "
        + "variable) must not alias the protected state. Return/assign a copy: `return m[k].clone();` (or `.cloneReadOnly()` "
        + "when an immutable result is acceptable). A single isolated object (a client, a caller) may leave as-is; an ARRAY or "
        + "MAP of them may not — copy the keys (`m.keys().clone()`) and fetch one element per lock instead.",

    // "invalid attempt to transfer a value into a 'lock' statement with restricted variable usage"
    "BCE3960": "A mutable value defined outside this lock must not be stored into protected state directly. Store a copy "
        + "instead: `m[k] = v.clone();`, or declare the incoming parameter/variable as `readonly & T` so it is immutable.",

    // "invalid invocation of a non-isolated function in a 'lock' statement with restricted variable usage"
    "BCE3961": "Only `isolated` functions may be called inside a lock that accesses an isolated variable or `self` of an "
        + "isolated object. Add the `isolated` qualifier to the called function, or move the call outside the lock.",

    // "invalid access of an 'isolated' variable outside a 'lock' statement"
    "BCE3962": "An `isolated` module variable may only be accessed inside a `lock` statement. Wrap the access in `lock { ... }`. "
        + "Remember: one lock may access only ONE isolated variable, and mutable values crossing the lock boundary must be cloned.",

    // "cannot access more than one variable for which usage is restricted in a single 'lock' statement"
    "BCE3964": "A single `lock` statement may access only ONE isolated variable (or `self` of an isolated object). Split the "
        + "logic into separate lock statements, copying needed values into locals in between (e.g. `int bv; lock { bv = b; } "
        + "lock { a += bv; }`). If two pieces of state must change atomically together, merge them into one protected value.",

    // "an uninitialized module variable declaration cannot be marked as 'isolated'"
    "BCE3965": "An `isolated` module variable must be initialized at the declaration. Add an initializer that is an isolated "
        + "expression (e.g. `isolated int[] data = [];`).",
};
