/**
 * Tests for the concurrency-safety coding rules injected into the agent system
 * prompt (src/features/ai/agent/concurrency-rules.ts) and their wiring into
 * getSystemPrompt (src/features/ai/agent/prompts.ts). The block also carries the
 * no-I/O-inside-lock rule with its fan-out snippet (compiled on Ballerina 2201.13.4)
 * and the clone-of-immutable rule (both remedies run on 2201.13.4); those assertions
 * keep the exact forms from drifting into variants that fail isolation analysis.
 *
 * The wiring check reads prompts.ts as source text instead of importing it:
 * prompts.ts transitively pulls in the extension-host module graph
 * (StateMachine, language client), which must not load in this jest suite.
 */
import * as fs from 'fs';
import * as path from 'path';
import { CONCURRENCY_CODING_RULES } from '../features/ai/agent/concurrency-rules';

describe('CONCURRENCY_CODING_RULES content', () => {
    test('is a markdown section with the expected heading', () => {
        expect(CONCURRENCY_CODING_RULES.startsWith('## Concurrency Safety and Shared State')).toBe(true);
    });

    test('teaches the isolated-variable + lock pattern for shared mutable state', () => {
        expect(CONCURRENCY_CODING_RULES).toMatch(/declare the variable `isolated`/);
        expect(CONCURRENCY_CODING_RULES).toMatch(/ONLY inside `lock \{ \}` blocks/);
        expect(CONCURRENCY_CODING_RULES).toMatch(/never mutated, declare it `final`/);
        expect(CONCURRENCY_CODING_RULES).toMatch(/initialize it at its declaration/);
    });

    test('never suggests final on configurable variables (compile error)', () => {
        expect(CONCURRENCY_CODING_RULES).toMatch(/NEVER write `final configurable`/);
        expect(CONCURRENCY_CODING_RULES).toMatch(/implicitly final/);
    });

    test('teaches that final alone is not sufficient for isolated reads', () => {
        expect(CONCURRENCY_CODING_RULES).toMatch(/`final` alone is NOT sufficient/);
        expect(CONCURRENCY_CODING_RULES).toMatch(/final int\[\] & readonly/);
    });

    test('teaches that a final variable of an isolated-object type is readable without a lock', () => {
        expect(CONCURRENCY_CODING_RULES).toMatch(/WITHOUT a lock when its static type is immutable \(`readonly`/);
        expect(CONCURRENCY_CODING_RULES).toMatch(/OR an isolated object/);
        expect(CONCURRENCY_CODING_RULES).toMatch(/most connector clients and listeners/);
        expect(CONCURRENCY_CODING_RULES).toMatch(/`ai:Agent`/);
        expect(CONCURRENCY_CODING_RULES).toMatch(/instances of your own `isolated class`/);
        expect(CONCURRENCY_CODING_RULES).toMatch(/Do NOT declare such a variable `isolated`, do NOT wrap its use in `lock \{ \}`, and do NOT add `& readonly` to it/);
    });

    test('scopes the transfer rule to mutable values and warns against readonly-for-lock values stored as mutable state', () => {
        expect(CONCURRENCY_CODING_RULES).toMatch(/a MUTABLE value leaving the lock/);
        expect(CONCURRENCY_CODING_RULES).toMatch(/single isolated objects \(a client, a caller\) may cross the boundary freely/);
        expect(CONCURRENCY_CODING_RULES).toMatch(/Do NOT make a value `readonly` merely to bring it into a lock/);
    });

    test('teaches the isolated-object rules for service state, including the method side', () => {
        expect(CONCURRENCY_CODING_RULES).toMatch(/mutable field `private`/);
        // concurrent dispatch needs BOTH the service and its methods isolated
        expect(CONCURRENCY_CODING_RULES).toMatch(/each resource\/remote method must ALSO satisfy isolated-function rules/);
        expect(CONCURRENCY_CODING_RULES).toMatch(/infers both the service and its methods as `isolated`/);
    });

    test('teaches the one-root-per-lock rule', () => {
        expect(CONCURRENCY_CODING_RULES).toMatch(/only ONE isolated root/);
        expect(CONCURRENCY_CODING_RULES).toMatch(/never access two `isolated` variables/);
    });

    test('teaches clone-on-transfer across lock boundaries, scoped to restricted locks only', () => {
        expect(CONCURRENCY_CODING_RULES).toMatch(/`value\.clone\(\)`/);
        expect(CONCURRENCY_CODING_RULES).toMatch(/`value\.cloneReadOnly\(\)`/);
        // transfer rules apply only to locks touching an isolated root — an
        // unconditional clone rule would sprinkle deep copies into ordinary locks
        expect(CONCURRENCY_CODING_RULES).toMatch(/A lock that touches an isolated root/);
        expect(CONCURRENCY_CODING_RULES).toMatch(/ordinary lock over non-isolated state has no such transfer restrictions/);
    });

    test('states the transfer-out rule precisely: single isolated object yes, array or map of them no', () => {
        expect(CONCURRENCY_CODING_RULES).toMatch(/may transfer OUT only an isolated expression — a `readonly` value or a SINGLE isolated object such as one `websocket:Caller`/);
        expect(CONCURRENCY_CODING_RULES).toMatch(/An ARRAY or MAP of callers is NOT isolated/);
        expect(CONCURRENCY_CODING_RULES).toMatch(/`lock \{ targets = subscribers\.toArray\(\); \}` fails to compile \(BCE3959\)/);
        expect(CONCURRENCY_CODING_RULES).toMatch(/`\.clone\(\)` does not apply to objects/);
    });

    test('forbids strand creation and worker message passing inside locks', () => {
        expect(CONCURRENCY_CODING_RULES).toMatch(/Never use `start`, named `worker` declarations, or worker send\/receive/);
    });

    test('teaches transitive isolated-function requirements', () => {
        expect(CONCURRENCY_CODING_RULES).toMatch(/must themselves be `isolated`/);
    });

    test('forbids I/O and remote calls inside a lock', () => {
        expect(CONCURRENCY_CODING_RULES).toMatch(/NEVER perform I\/O or remote calls \(`->writeMessage`, HTTP\/DB client calls, `runtime:sleep`\) inside a `lock`/);
    });

    test('teaches the compiled fan-out pattern verbatim', () => {
        expect(CONCURRENCY_CODING_RULES).toContain('connectionIds = subscribers.keys().clone();');
        expect(CONCURRENCY_CODING_RULES).toContain('caller = subscribers[connectionId];');
        expect(CONCURRENCY_CODING_RULES).toContain('websocket:Error? result = caller->writeMessage(event); // I/O OUTSIDE the lock');
        expect(CONCURRENCY_CODING_RULES).toContain('_ = subscribers.removeIfHasKey(connectionId);');
        expect(CONCURRENCY_CODING_RULES).toMatch(/isolated function broadcast\(readonly & OrderUpdate event\)/);
        expect(CONCURRENCY_CODING_RULES).toMatch(/pass it as `readonly` so it can enter the locks freely/);
    });

    test('never puts the write inside a lock in the snippet', () => {
        const snippet = CONCURRENCY_CODING_RULES.slice(CONCURRENCY_CODING_RULES.indexOf('```ballerina'), CONCURRENCY_CODING_RULES.lastIndexOf('```'));
        // Every lock block in the snippet is a single statement that touches only the map.
        const lockBodies = [...snippet.matchAll(/lock \{\n([\s\S]*?)\n\s*\}/g)].map((m) => m[1]);
        expect(lockBodies).toHaveLength(3);
        for (const body of lockBodies) {
            expect(body).not.toMatch(/->/);
            expect(body).toMatch(/subscribers/);
        }
    });

    test('states that cloning an immutable value returns the same immutable value', () => {
        expect(CONCURRENCY_CODING_RULES).toMatch(/`clone\(\)` and `cloneReadOnly\(\)` on an ALREADY immutable value return the SAME immutable value, NOT a mutable copy/);
    });

    test('names the runtime panic and says diagnostics cannot catch it', () => {
        expect(CONCURRENCY_CODING_RULES).toMatch(/`\{ballerina\/lang\.array\}InvalidUpdate` \("modification not allowed on readonly value"\)/);
        expect(CONCURRENCY_CODING_RULES).toMatch(/diagnostics cannot catch this/);
    });

    test('teaches the verified clone remedies in their exact forms', () => {
        expect(CONCURRENCY_CODING_RULES).toContain('`string[] fresh = (from string q in roQueues select q).clone();`');
        expect(CONCURRENCY_CODING_RULES).toContain('`string[] fresh = check roQueues.cloneWithType();`');
        expect(CONCURRENCY_CODING_RULES).toMatch(/a bare query expression there is a compile error/);
        expect(CONCURRENCY_CODING_RULES).toMatch(/in a function that can return `error`/);
        expect(CONCURRENCY_CODING_RULES).toMatch(/Never store a readonly-derived value where later code will mutate it/);
    });

    test('contains no unresolved interpolations or stray backtick escapes', () => {
        expect(CONCURRENCY_CODING_RULES).not.toContain('${');
        expect(CONCURRENCY_CODING_RULES).not.toContain('\\`');
    });
});

describe('system prompt wiring', () => {
    const promptsSource = fs.readFileSync(
        path.join(__dirname, '../features/ai/agent/prompts.ts'),
        'utf-8',
    );

    test('prompts.ts imports the rules constant', () => {
        expect(promptsSource).toContain(
            'import { CONCURRENCY_CODING_RULES } from "./concurrency-rules"');
    });

    test('prompts.ts interpolates the rules into the system prompt exactly once', () => {
        expect(promptsSource.split('${CONCURRENCY_CODING_RULES}')).toHaveLength(2);
    });

    test('rules are placed between Coding Rules and File modifications', () => {
        const codingRulesIdx = promptsSource.indexOf('## Coding Rules');
        const concurrencyIdx = promptsSource.indexOf('${CONCURRENCY_CODING_RULES}');
        const fileModsIdx = promptsSource.indexOf('## File modifications');
        expect(codingRulesIdx).toBeGreaterThan(-1);
        expect(concurrencyIdx).toBeGreaterThan(codingRulesIdx);
        expect(fileModsIdx).toBeGreaterThan(concurrencyIdx);
    });

    test('the absorbed lock-I/O and immutable-clone rules are no longer wired separately', () => {
        expect(promptsSource).not.toContain('LOCK_IO_RULE');
        expect(promptsSource).not.toContain('IMMUTABLE_CLONE_RULE');
    });
});
