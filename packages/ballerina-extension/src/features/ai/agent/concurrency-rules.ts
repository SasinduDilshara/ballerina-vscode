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
 * Kept in its own module (no imports) so it can be unit-tested without loading
 * the extension-host module graph. Interpolated into the system prompt by
 * getSystemPrompt() in ./prompts.ts.
 */
export const CONCURRENCY_CODING_RULES = `## Concurrency Safety and Shared State
Ballerina enforces isolation rules at compile time so services can safely handle requests concurrently. Follow these rules when the code involves module-level state, service fields, or workers:
- \`configurable\` variables are implicitly final and safe to read from any isolated context — NEVER write \`final configurable\` (it is a compile error).
- For other module-level variables: if the value is never mutated, declare it \`final\`; if it is also read from an \`isolated\` function or a service method, its type must ALSO be immutable — \`final\` alone is not sufficient (e.g. \`final int[] & readonly defaults = [1, 2];\`). If it is shared MUTABLE state, declare the variable \`isolated\` (e.g. \`isolated int[] requests = [];\`) and access it ONLY inside \`lock { }\` blocks. An \`isolated\` variable must be initialized at its declaration.
- In a service or class holding mutable state, declare every mutable field \`private\`, initialize it with a fresh literal/constructor, and access it via \`self\` ONLY inside \`lock { }\` blocks. For requests to be dispatched concurrently, each resource/remote method must ALSO satisfy isolated-function rules itself: it may only call \`isolated\` functions and must not touch non-final module-level mutable state (use \`isolated\` variables with \`lock { }\` for that). The compiler then infers both the service and its methods as \`isolated\`.
- One \`lock\` block may protect only ONE isolated root: never access two \`isolated\` variables (or an \`isolated\` variable plus \`self\`) in the same lock. Use separate lock statements and pass values between them via local variables. If two pieces of state must change together atomically, store them in ONE protected value (a single record/map behind one isolated variable).
- In a lock that touches an isolated root (an \`isolated\` variable or \`self\` of an isolated object), a value crossing the lock boundary must not alias the protected state: when returning or assigning a value OUT of such a lock, or bringing an outside mutable value INTO it, use \`value.clone()\` (or \`value.cloneReadOnly()\`). Immutable (\`readonly\`) values may cross freely. An ordinary lock over non-isolated state has no such transfer restrictions — do not add gratuitous clones there.
- Never use \`start\`, named \`worker\` declarations, or worker send/receive actions (\`->\`/\`<-\`) inside a \`lock\` block. Read the needed values into locals inside the lock, then do the async work after it.
- Functions called from an \`isolated\` function, or from inside a lock protecting isolated state, must themselves be \`isolated\`. Write helper functions as \`isolated\` when they only work on their parameters and local state.
- Keep lock blocks minimal: only the shared-state read/write belongs inside; perform I/O and remote calls outside the lock.`;
