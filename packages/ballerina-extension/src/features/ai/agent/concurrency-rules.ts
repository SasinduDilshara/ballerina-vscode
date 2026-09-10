/**
 * System-prompt rules for writing concurrency-safe Ballerina code.
 *
 * These rules teach the agent to write code that satisfies the compiler's
 * IsolationAnalyzer up front (isolated functions/variables/objects, lock
 * statement restrictions, transfer in/out rules) so services are inferred
 * `isolated` and dispatched concurrently, and so the agent avoids the most
 * common isolation errors (BCE3943, BCE3956-BCE3964, BCE2080/BCE2081,
 * BCE4042/BCE4043) instead of hitting them and repairing after the fact.
 *
 * Also covers: no I/O inside a lock and the key-snapshot fan-out pattern that
 * compiles (verified on Ballerina 2201.13.4), and cloning of immutable values
 * (clone() of an immutable value returns the same value; rebuild with a query
 * expression + .clone() or cloneWithType(), both verified on 2201.13.4).
 *
 * Kept in its own module (no imports) so it can be unit-tested without loading
 * the extension-host module graph. Interpolated into the system prompt by
 * getSystemPrompt() in ./prompts.ts.
 */
export const CONCURRENCY_CODING_RULES = `## Concurrency Safety and Shared State
Apply these rules whenever the code has module-level state, service or class fields, or workers.

### Module-level variables
- \`configurable\` variables are implicitly final and readable from any isolated context without a lock. NEVER write \`final configurable\` (compile error).
- Declare never-mutated values \`final\`.
- A \`final\` variable is readable from \`isolated\` functions and service methods WITHOUT a lock only when its type is one of:
  - A literal (string, int, boolean, decimal, float, byte).
  - \`readonly\`, e.g. \`final int[] & readonly defaults = [1, 2];\`
  - an isolated object: most clients and listeners defined by the libraries are isolated (e.g. \`final http:Client httpClient = check new (url);\`), \`ai:Agent\`, or an instance of your own \`isolated class\`.
    - Hint: If you use a connector or listener that defined from a Library, first assume it is isolated and the compiler will tell you if it is not. If it is not, mark the module variable as isolated and wrap all access in \`lock { }\`.
  - For such a variable, do NOT declare it \`isolated\`, do NOT wrap its use in \`lock { }\`, and do NOT add \`& readonly\`.
- \`final\` alone is still non-isolated mutable state for other types: \`final map<string>\` or \`final int[]\`.
    - Shared MUTABLE state: declare the variable \`isolated\` with an initializer (\`isolated int[] requests = [];\`) and access it ONLY inside \`lock { }\`.

### Services and classes with mutable state
- Declare every mutable field \`private\`, initialize it with a fresh literal/constructor, and access it via \`self\` ONLY inside \`lock { }\`. Declare the class \`isolated class\` when a module-level \`final\` variable will hold it.
- For concurrent dispatch, each resource/remote method must ALSO satisfy isolated-function rules: call only \`isolated\` functions and never touch non-final module-level mutable state (use \`isolated\` variables with \`lock { }\` instead). The compiler then infers the service and its methods as \`isolated\`.
- Functions called from an \`isolated\` function, or from inside a lock over isolated state, must themselves be \`isolated\`. Write helpers as \`isolated\` when they only use their parameters and local state.

### Lock statements
- ONE isolated root per lock: never access two \`isolated\` variables (or an \`isolated\` variable plus \`self\`) in the same lock. Use separate locks and pass values between them via locals; if two pieces of state must change atomically, keep them in ONE protected value (a single record/map behind one isolated variable).
- Transfer rules for a lock over an isolated root (an \`isolated\` variable or \`self\` of an isolated object): only an isolated expression may cross the boundary. \`readonly\` values and a SINGLE isolated object (a client, a caller) pass freely; a MUTABLE value leaving the lock (returned or assigned to an outer variable) must be copied with \`value.clone()\` (or \`value.cloneReadOnly()\` when an immutable result is acceptable). An ARRAY or MAP of callers is NOT isolated: \`lock { targets = subscribers.toArray(); }\` fails to compile (BCE3959), and \`.clone()\` does not apply to objects. An ordinary lock over non-isolated state has no transfer restrictions — do not add clones there.
- Never use \`start\`, named \`worker\` declarations, or worker send/receive (\`->\`/\`<-\`) inside a lock. Read the needed values into locals inside the lock, then do the async work after it.
- Keep locks minimal: only the shared-state read/write belongs inside. NEVER perform I/O or remote calls (\`->writeMessage\`, HTTP/DB client calls, \`runtime:sleep\`) inside a lock. Fan-out over an \`isolated map<websocket:Caller>\`: build the outgoing value as \`readonly\` before the loop; copy the keys in one short lock (\`connectionIds = subscribers.keys().clone();\` — \`string[]\` is mutable, so \`.clone()\` is required); for each key fetch ONE caller in a short lock (a single isolated object leaves as-is); call \`->writeMessage\` OUTSIDE the lock; remove a failed caller in its own short lock (\`_ = subscribers.removeIfHasKey(connectionId);\`).

### Cloning readonly values
- \`clone()\` and \`cloneReadOnly()\` on an ALREADY immutable value return the SAME immutable value, not a mutable copy. A value made \`readonly\` (a \`readonly & T\` parameter, a \`cloneReadOnly()\` result, a \`readonly\` record field) stays immutable after \`.clone()\`; storing it in a mutable-typed field or variable makes a later \`push\`/member update panic at run time with \`{ballerina/lang.array}InvalidUpdate\` ("modification not allowed on readonly value"), which diagnostics cannot catch. Never store a readonly-derived value where later code will mutate it.
- Do NOT make a value \`readonly\` merely to bring it into a lock when it will later be stored as mutable state. To get genuinely mutable state from a readonly value, REBUILD it: \`string[] fresh = (from string q in roQueues select q).clone();\` — the trailing \`.clone()\` makes it an isolated expression, so this also works when assigning straight to an \`isolated\` variable inside a lock (a bare query expression there is a compile error) — or, in a function that can return \`error\`, \`string[] fresh = check roQueues.cloneWithType();\`.`;
