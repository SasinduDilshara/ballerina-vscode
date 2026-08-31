/**
 * Tests for the concurrency-safety coding rules injected into the agent system
 * prompt (src/features/ai/agent/concurrency-rules.ts) and their wiring into
 * getSystemPrompt (src/features/ai/agent/prompts.ts).
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
    });

    test('never suggests final on configurable variables (compile error)', () => {
        expect(CONCURRENCY_CODING_RULES).toMatch(/NEVER write `final configurable`/);
        expect(CONCURRENCY_CODING_RULES).toMatch(/implicitly final/);
    });

    test('teaches that final alone is not sufficient for isolated reads', () => {
        expect(CONCURRENCY_CODING_RULES).toMatch(/`final` alone is not sufficient/);
        expect(CONCURRENCY_CODING_RULES).toMatch(/final int\[\] & readonly/);
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
        expect(CONCURRENCY_CODING_RULES).toMatch(/In a lock that touches an isolated root/);
        expect(CONCURRENCY_CODING_RULES).toMatch(/ordinary lock over non-isolated state has no such transfer restrictions/);
    });

    test('forbids strand creation and worker message passing inside locks', () => {
        expect(CONCURRENCY_CODING_RULES).toMatch(/Never use `start`, named `worker` declarations, or worker send\/receive/);
    });

    test('teaches transitive isolated-function requirements', () => {
        expect(CONCURRENCY_CODING_RULES).toMatch(/must themselves be `isolated`/);
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

    test('prompts.ts interpolates the rules into the system prompt', () => {
        expect(promptsSource).toContain('${CONCURRENCY_CODING_RULES}');
    });

    test('rules are placed between Coding Rules and File modifications', () => {
        const codingRulesIdx = promptsSource.indexOf('## Coding Rules');
        const concurrencyIdx = promptsSource.indexOf('${CONCURRENCY_CODING_RULES}');
        const fileModsIdx = promptsSource.indexOf('## File modifications');
        expect(codingRulesIdx).toBeGreaterThan(-1);
        expect(concurrencyIdx).toBeGreaterThan(codingRulesIdx);
        expect(fileModsIdx).toBeGreaterThan(concurrencyIdx);
    });
});
