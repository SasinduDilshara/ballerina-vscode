import type { DiagnosticEntry, Diagnostics } from '@wso2/ballerina-core';
import * as path from 'path';

/**
 * Diagnostic entry enriched with a resolving hint
 */
export interface EnrichedDiagnostic extends DiagnosticEntry {
    hint?: string;
}

/**
 * Service-concurrency hint codes (severity: Hint, not Error).
 *
 * These are emitted by the compiler's IsolationAnalyzer when a service's
 * resource/remote methods cannot be dispatched concurrently because the
 * service and/or the method could not be declared or inferred `isolated`.
 * The code still compiles, so they must never be counted as compilation
 * errors — they are surfaced separately as `concurrencyHints`.
 */
export const CONCURRENCY_HINT_CODES: ReadonlySet<string> = new Set([
    "BCH2003", // concurrent calls will not be made to this method since the service and the method are not 'isolated'
    "BCH2004", // concurrent calls will not be made to this method since the service is not an 'isolated' service
    "BCH2005", // concurrent calls will not be made to this method since the method is not an 'isolated' method
]);

const SERVICE_ISOLATION_FIX =
    "To enable concurrent dispatch, make the service isolated: (1) declare every mutable service field `private` " +
    "and access it via `self` only inside `lock { }` blocks (clone values crossing the lock boundary with " +
    "`.clone()`/`.cloneReadOnly()`); (2) ensure field initializers are isolated expressions (fresh literals/constructors); " +
    "(3) ensure each resource/remote method only calls `isolated` functions and accesses no non-final module-level mutable state. " +
    "Isolation is then inferred automatically — explicit `isolated` qualifiers on the service and methods also work. " +
    "Only make this change if the user's request involves concurrency, throughput, or shared state.";

/**
 * Map of Ballerina diagnostic codes to resolving hints.
 *
 * Each entry maps a diagnostic code (e.g., "BCE3943") to a directive hint on how to
 * resolve it. Hints ride along with the diagnostics returned by the
 * getCompilationErrors tool so the agent can apply the canonical fix instead of
 * guessing at the compiler's isolation/lock terminology.
 *
 * The isolation/lock/concurrency entries were derived from the ballerina-lang
 * compiler sources (IsolationAnalyzer.java, DiagnosticErrorCode.java,
 * compiler.properties) and the language server's quick-fix code actions
 * (AddIsolatedQualifierCodeAction, AddLockCodeAction, AddReadonlyCodeAction,
 * CloneValueCodeAction, ConvertToReadonlyCloneCodeAction). The comment above each
 * entry quotes the compiler's message template for that code.
 */
export const DIAGNOSTIC_HINTS: Readonly<Record<string, string>> = {
    "BCE2000": "This usually indicates a missing import statement. Please ensure that all necessary modules are imported in each file where they are used.",

    // ---- Lock statement structural rules ----

    // "cannot use a named worker inside a lock statement"
    "BCE2080": "A lock statement must not start new strands. Move the `worker` declaration outside the lock: " +
        "read the protected values into local variables inside `lock { }`, close the lock, then declare the worker using those locals.",

    // "cannot use an async call inside a lock statement"
    "BCE2081": "`start` is not allowed inside a `lock` statement. Read the protected values into local variables inside " +
        "`lock { }`, close the lock, then call `start` with the locals (e.g. `int localN; lock { localN = n; } future<int> f = start compute(localN);`).",

    // "using send action within the lock statement is not allowed to prevent possible deadlocks"
    "BCE4042": "Worker send (`->`) is not allowed inside a `lock` statement (deadlock prevention). Use the lock only to " +
        "read/write the shared state into local variables, then perform the send outside the lock.",

    // "using receive action within the lock statement is not allowed to prevent possible deadlocks"
    "BCE4043": "Worker receive (`<-`) is not allowed inside a `lock` statement (deadlock prevention). Receive the message " +
        "into a local variable outside the lock, then update the shared state inside a `lock { }` block.",

    // ---- Isolated functions ----

    // "invalid access of mutable storage in an 'isolated' function"
    "BCE3943": "An `isolated` function may only access module-level state that is (a) `final` with an immutable " +
        "(`readonly`) or isolated-object type, or (b) declared `isolated` and accessed only inside `lock { }`. " +
        "Fix: if the variable is never mutated, declare it `final` (immutable type); if it is shared mutable state, declare it " +
        "`isolated` (e.g. `isolated int[] stack = [];`) and wrap every access in `lock { }`. `configurable` variables are already safe. " +
        "Do NOT simply drop the `isolated` qualifier from a resource/remote method — that disables concurrent dispatch.",

    // "invalid access of mutable storage in the default value of a record field"
    "BCE3944": "A record field's default value must be an isolated expression. Replace the reference to mutable module " +
        "state with a literal or a `final` readonly value, or require the field explicitly and set it where the record is created.",

    // "invalid access of mutable storage in the initializer of an object field"
    "BCE3945": "An object field initializer must be an isolated expression. Use a literal or `final` readonly value, or " +
        "move the assignment into a non-`isolated` `init()` method (inside an `isolated` init the assigned value must " +
        "still be an isolated expression, e.g. `self.f = v.clone();`).",

    // "incompatible types: expected an 'isolated' function"
    "BCE3946": "The function value passed here must be `isolated` (e.g. arguments to langlib functions like `array:forEach` " +
        "called from an isolated context). Add the `isolated` qualifier to the function or anonymous function being passed.",

    // "invalid invocation of a non-isolated function in an 'isolated' function"
    "BCE3947": "An `isolated` function may only call `isolated` functions. Add the `isolated` qualifier to the called " +
        "function (its own body must also satisfy isolation rules, transitively). If the callee is third-party and not isolated, " +
        "restructure so the isolated function does not call it.",

    // "invalid invocation of a non-isolated function in the default value of a record field"
    "BCE3948": "A function invoked in a record field's default value must be `isolated`. Make the called function `isolated`, " +
        "or replace the default with a literal and compute the value where the record is created.",

    // "invalid invocation of a non-isolated function in the initializer of an object field"
    "BCE3949": "A function invoked in an object field initializer must be `isolated`. Make the called function `isolated`, " +
        "or move the computation into a non-`isolated` `init()` method (inside an `isolated` init the called function " +
        "must still be `isolated`).",

    // "invalid non-isolated initialization expression in an 'isolated' function"
    "BCE3950": "`new` of a class whose `init` method is not `isolated` is not allowed here. Add the `isolated` qualifier " +
        "to the class's `init` method (and make sure `init` itself follows isolated-function rules).",

    // "invalid non-isolated initialization expression in the default value of a record field"
    "BCE3951": "The class instantiated in this record field default must have an `isolated` `init` method. Make `init` isolated, " +
        "or drop the default and construct the value where the record is created.",

    // "invalid non-isolated initialization expression in the initializer of an object field"
    "BCE3952": "The class instantiated in this object field initializer must have an `isolated` `init` method. Make `init` " +
        "isolated, or move the construction into a non-`isolated` `init()` method of the enclosing object.",

    // "'strand' annotation not allowed in a ... in an 'isolated' function"
    "BCE3953": "Remove the `@strand` annotation — it is not allowed in an `isolated` function (and is deprecated in general).",

    // "invalid start action calling a non-isolated function in an 'isolated' function"
    "BCE3954": "`start` inside an `isolated` function may only call `isolated` functions. Add the `isolated` qualifier to " +
        "the function being started.",

    // "invalid start action accessing a non isolated expression in an argument..."
    "BCE3955": "Arguments to `start` in an `isolated` function must be isolated expressions. Pass an immutable value, or a " +
        "fresh copy of a local/parameter value: `start process(data.clone())` (or `.cloneReadOnly()`). Cloning module-level " +
        "mutable state directly in the argument does NOT help (that reports BCE3943) — read it into a local inside a " +
        "`lock { }` first, then pass a clone of that local: `start process(localData.clone())`.",

    // ---- Isolated objects ----

    // "invalid non-private mutable field in an isolated object"
    "BCE3956": "Every mutable field of an isolated object/class must be `private`. Add the `private` qualifier, or make the " +
        "field `final` with an immutable (`readonly`) or isolated-object type if it is never reassigned.",

    // "invalid access of a mutable field of an 'isolated' object outside a 'lock' statement"
    "BCE3957": "Access to a mutable `self` field of an isolated object must be inside a `lock` statement. Wrap the statement(s) " +
        "in `lock { ... }` (group nearby accesses of the field into one lock; values leaving the lock must be cloned — see BCE3959).",

    // "invalid initial value expression: expected an isolated expression"
    "BCE3958": "The initial value of isolated state must be an isolated expression. This fires on (a) the initializer of an " +
        "`isolated` variable, or (b) an assignment to a `self` field inside an `isolated` class's `init` method. Use a " +
        "literal, a fresh constructor (e.g. `[]`, `{}`, `new Foo()` with an isolated `init`), or a copy — most commonly " +
        "change `self.data = data;` to `self.data = data.clone();` (or `.cloneReadOnly()`).",

    // ---- Restricted lock (transfer) rules ----

    // "invalid attempt to transfer out a value from a 'lock' statement with restricted variable usage: expected an isolated expression"
    "BCE3959": "A value leaving a lock that protects an isolated variable or `self` (via `return` or assignment to an outer " +
        "variable) must not alias the protected state. Return/assign a copy: `return m[k].clone();` (or `.cloneReadOnly()` " +
        "when an immutable result is acceptable). Alternatively declare the protected storage's member type as `T & readonly` " +
        "so reads are already immutable.",

    // "invalid attempt to transfer a value into a 'lock' statement with restricted variable usage"
    "BCE3960": "A mutable value defined outside this lock must not be referenced inside it in a non-isolated expression " +
        "(storing it — or even aliasing it to a local — could create an outside alias to protected state). Use a copy " +
        "instead: `m[k] = v.clone();`, or declare the incoming parameter/variable as `readonly & T` so it is immutable.",

    // "invalid invocation of a non-isolated function in a 'lock' statement with restricted variable usage"
    "BCE3961": "Only `isolated` functions may be called inside a lock that accesses an isolated variable or `self` of an " +
        "isolated object. Add the `isolated` qualifier to the called function, or move the call outside the lock.",

    // "invalid access of an 'isolated' variable outside a 'lock' statement"
    "BCE3962": "An `isolated` module variable may only be accessed inside a `lock` statement. Wrap the access in `lock { ... }`. " +
        "Remember: one lock may access only ONE isolated variable, and values crossing the lock boundary must be cloned.",

    // "cannot assign to a variable outside the 'lock' statement with restricted variable usage, if not just a variable name"
    "BCE3963": "Inside a lock with restricted variable usage, a destructuring assignment to outer state is not allowed — " +
        "an assignment out of the lock must target a plain variable name. Assign the (cloned) value to a simple outer " +
        "variable inside the lock, or capture it in a local and update the outer structure after the lock. " +
        "(Assignments through member/index access on outer state report BCE3960 instead.)",

    // "cannot access more than one variable for which usage is restricted in a single 'lock' statement"
    "BCE3964": "A single `lock` statement may access only ONE isolated variable (or `self` of an isolated object). Split the " +
        "logic into separate lock statements, copying needed values into locals in between (e.g. `int bv; lock { bv = b; } " +
        "lock { a += bv; }`). If two pieces of state must change atomically together, merge them into one protected value " +
        "(e.g. a single isolated record/map holding both).",

    // "an uninitialized module variable declaration cannot be marked as 'isolated'"
    "BCE3965": "An `isolated` module variable must be initialized at the declaration. Add an initializer that is an isolated " +
        "expression (e.g. `isolated int[] data = [];`).",

    // "only a simple variable can be marked as 'isolated'"
    "BCE3966": "`isolated` can only be applied to a simple variable declaration — not to destructuring/binding patterns. " +
        "Declare a plain variable instead.",

    // ---- Match guards ----

    // "cannot call a non-isolated function/method in a match guard when the type of the action/expression being matched is not a subtype of 'readonly'"
    "BCE4018": "A function called in a match guard must be `isolated` when the matched value is not `readonly`. Add the " +
        "`isolated` qualifier to the guard function, or match over a `readonly` value.",

    // "cannot call a function/method in a match guard with an argument of a type that is not a subtype of 'readonly'"
    "BCE4019": "Arguments to a function call in a match guard must be `readonly` when the matched value is not itself " +
        "`readonly`. Pass an immutable copy: replace `arg` with `arg.cloneReadOnly()`, or match over a `readonly` value.",

    // "invalid access of an isolated variable outside a lock statement in the default value of a record field"
    "BCE4025": "A record field's default value cannot reference an `isolated` variable directly. Use a literal default, or " +
        "keep the default by calling an `isolated` function that reads the variable inside `lock { }` (e.g. " +
        "`type R record {| int f = readCount(); |};` where `isolated function readCount() returns int { lock { return count; } }`).",

    // ---- Service concurrency hints (not errors; surfaced as concurrencyHints) ----

    // "concurrent calls will not be made to this method since the service and the method are not 'isolated'"
    "BCH2003": "Neither the service nor this method is `isolated`, so the listener serializes calls to it. " + SERVICE_ISOLATION_FIX,

    // "concurrent calls will not be made to this method since the service is not an 'isolated' service"
    "BCH2004": "This method is `isolated` but the service is not, so the listener serializes calls to it. The blocker is the " +
        "service's state: make every mutable service field `private`, initialized with an isolated expression, and accessed via " +
        "`self` only inside `lock { }`. " + SERVICE_ISOLATION_FIX,

    // "concurrent calls will not be made to this method since the method is not an 'isolated' method"
    "BCH2005": "The service is isolated but this method is not, so the listener serializes calls to it. The blocker is the " +
        "method body: it must only call `isolated` functions and must not access non-final module-level mutable state (use " +
        "`isolated` variables with `lock { }` where shared state is needed). " + SERVICE_ISOLATION_FIX,
};

/**
 * Builds the note appended to the diagnostics tool message when
 * service-concurrency hints (BCH2003–BCH2005) are present. Explicitly framed as
 * non-blocking so the agent does not loop trying to drive the diagnostic count
 * to zero, and does not refactor services the user never asked about.
 */
export function buildConcurrencyHintNote(hintCount: number): string {
    if (hintCount === 0) {
        return "";
    }
    return ` Additionally, ${hintCount} service-concurrency hint(s) were reported (see concurrencyHints). ` +
        `These are NOT compilation errors — the code compiles — but the listed resource/remote methods will NOT ` +
        `be dispatched concurrently because the service and/or method is not 'isolated'. Address them ONLY if the ` +
        `user's request involves concurrency, throughput, or shared mutable state; otherwise leave the code as is ` +
        `(you may briefly mention the limitation to the user).`;
}

/**
 * Result of transforming raw language-server diagnostics for the agent.
 */
export interface TransformedDiagnostics {
    /** Compilation errors (severity === 1), enriched with resolving hints. */
    errors: EnrichedDiagnostic[];
    /**
     * Service-concurrency hints (BCH2003–BCH2005), any severity. The code
     * compiles — these indicate that requests will be dispatched serially.
     */
    concurrencyHints: EnrichedDiagnostic[];
}

/**
 * Converts language server Diagnostics to EnrichedDiagnostic entries with hints.
 *
 * Routing: non-error-severity concurrency hint codes (BCH2003–BCH2005) go to
 * `concurrencyHints`; everything else is included in `errors` only when it is
 * an error-level diagnostic (severity === 1). A BCH code at error severity
 * (unreachable today — the compiler hardcodes HINT — but defensive) stays in
 * `errors` so a real error can never be downgraded to an ignorable hint.
 */
export function transformDiagnostics(diagnostics: Diagnostics[]): TransformedDiagnostics {
    const errors: EnrichedDiagnostic[] = [];
    const concurrencyHints: EnrichedDiagnostic[] = [];

    for (const diagParam of diagnostics) {
        for (const diag of diagParam.diagnostics) {
            const code = diag.code === undefined || diag.code === null ? "" : diag.code.toString();
            const isConcurrencyHint = CONCURRENCY_HINT_CODES.has(code) && diag.severity !== 1;

            // Only include error-level diagnostics (plus the concurrency hints)
            if (diag.severity !== 1 && !isConcurrencyHint) {
                continue;
            }

            const fileName = path.basename(diagParam.uri);
            const msgPrefix = `[${fileName}:${diag.range.start.line},${diag.range.start.character}:${diag.range.end.line},${diag.range.end.character}] `;

            const diagnosticEntry: EnrichedDiagnostic = {
                message: msgPrefix + diag.message
            };
            if (code !== "") {
                diagnosticEntry.code = code;
            }

            // Add hint if available for this diagnostic code
            const hint = DIAGNOSTIC_HINTS[code];
            if (hint) {
                diagnosticEntry.hint = hint;
            }

            if (isConcurrencyHint) {
                concurrencyHints.push(diagnosticEntry);
            } else {
                errors.push(diagnosticEntry);
            }
        }
    }

    return { errors, concurrencyHints };
}
