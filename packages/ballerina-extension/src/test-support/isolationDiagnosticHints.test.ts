/**
 * Tests for the isolation/lock/concurrency diagnostic hint catalog and the
 * diagnostics transform (src/features/ai/agent/tools/diagnostic-hints.ts).
 *
 * The hint catalog was derived from the ballerina-lang compiler sources
 * (DiagnosticErrorCode.java, DiagnosticHintCode.java, compiler.properties,
 * IsolationAnalyzer.java) — these tests pin the exact code set and the
 * canonical fix each hint must teach, so a hint can't silently drift away
 * from the compiler's remediation (e.g. BCE3959 must mention clone()).
 */
import type { Diagnostics } from '@wso2/ballerina-core';
import {
    buildConcurrencyHintNote,
    CONCURRENCY_HINT_CODES,
    DIAGNOSTIC_HINTS,
    transformDiagnostics,
} from '../features/ai/agent/tools/diagnostic-hints';

/**
 * The complete, verified set of isolation/lock/concurrency diagnostic codes
 * (from ballerina-lang master: DiagnosticErrorCode.java + DiagnosticHintCode.java).
 */
const ISOLATION_ERROR_CODES = [
    // lock statement structural rules
    'BCE2080', // named worker inside lock
    'BCE2081', // async call (start) inside lock
    'BCE4042', // worker send inside lock
    'BCE4043', // worker receive inside lock
    // isolated functions
    'BCE3943', // mutable storage access in isolated function
    'BCE3944', // mutable storage access in record field default
    'BCE3945', // mutable storage access in object field initializer
    'BCE3946', // expected an isolated function
    'BCE3947', // non-isolated invocation in isolated function
    'BCE3948', // non-isolated invocation in record field default
    'BCE3949', // non-isolated invocation in object field initializer
    'BCE3950', // non-isolated init expression in isolated function
    'BCE3951', // non-isolated init expression in record field default
    'BCE3952', // non-isolated init expression in object field initializer
    'BCE3953', // @strand annotation in isolated function
    'BCE3954', // start of non-isolated function in isolated function
    'BCE3955', // non-isolated expression in async call args
    // isolated objects
    'BCE3956', // non-private mutable field in isolated object
    'BCE3957', // mutable field access outside lock
    'BCE3958', // initial value not an isolated expression
    // restricted lock (transfer) rules
    'BCE3959', // transfer out of lock
    'BCE3960', // transfer into lock
    'BCE3961', // non-isolated invocation in restricted lock
    'BCE3962', // isolated variable access outside lock
    'BCE3963', // invalid assignment in restricted lock
    'BCE3964', // multiple restricted vars in one lock
    'BCE3965', // uninitialized isolated module variable
    'BCE3966', // isolated on non-simple variable
    // match guards
    'BCE4018', // non-isolated call in match guard
    'BCE4019', // mutable args in match guard call
    'BCE4025', // isolated variable access in record field default
] as const;

const CONCURRENCY_CODES = ['BCH2003', 'BCH2004', 'BCH2005'] as const;

// ---------------------------------------------------------------------------
// Catalog integrity
// ---------------------------------------------------------------------------

describe('DIAGNOSTIC_HINTS catalog', () => {
    test('contains every verified isolation/lock error code', () => {
        for (const code of ISOLATION_ERROR_CODES) {
            expect(DIAGNOSTIC_HINTS[code]).toBeDefined();
        }
    });

    test('contains every service-concurrency hint code', () => {
        for (const code of CONCURRENCY_CODES) {
            expect(DIAGNOSTIC_HINTS[code]).toBeDefined();
        }
    });

    test('contains exactly the expected code set (no unverified codes)', () => {
        const expected = new Set<string>([
            'BCE2000', // pre-existing missing-import hint
            ...ISOLATION_ERROR_CODES,
            ...CONCURRENCY_CODES,
        ]);
        expect(new Set(Object.keys(DIAGNOSTIC_HINTS))).toEqual(expected);
    });

    test('all keys are well-formed Ballerina diagnostic codes', () => {
        for (const code of Object.keys(DIAGNOSTIC_HINTS)) {
            expect(code).toMatch(/^BC[EH]\d{4}$/);
        }
    });

    test('all hints are substantial, single-purpose strings', () => {
        for (const [code, hint] of Object.entries(DIAGNOSTIC_HINTS)) {
            expect(typeof hint).toBe('string');
            // long enough to be actionable, short enough to not blow up tool output
            expect(hint.length).toBeGreaterThan(40);
            expect(hint.length).toBeLessThan(1000);
            // no accidental unresolved template placeholders
            expect(hint).not.toContain('${');
            expect(hint).not.toContain('undefined');
            // hints must be self-contained prose, not truncated
            expect(hint.trim()).toBe(hint);
            expect(code).toBeTruthy();
        }
    });

    test('CONCURRENCY_HINT_CODES is exactly BCH2003–BCH2005', () => {
        expect([...CONCURRENCY_HINT_CODES].sort()).toEqual([...CONCURRENCY_CODES]);
    });
});

describe('DIAGNOSTIC_HINTS canonical fixes', () => {
    // Each hint must teach the fix the compiler/LS code actions prescribe.
    const mustMention: Array<[string, RegExp[]]> = [
        // pre-existing missing-import hint
        ['BCE2000', [/import/i]],
        // structural lock rules: move strand work outside the lock
        ['BCE2080', [/worker/i, /outside the lock/i]],
        ['BCE2081', [/`start`/, /lock/i, /local/i]],
        ['BCE4042', [/send/i, /outside the lock/i]],
        ['BCE4043', [/receive/i, /outside the lock/i]],
        // isolated function rules
        ['BCE3943', [/final/, /isolated/, /lock \{ \}/, /configurable/]],
        ['BCE3944', [/isolated expression/, /literal/i]],
        ['BCE3945', [/literal/i, /non-`isolated` `init\(\)`/, /clone\(\)/]],
        ['BCE3946', [/isolated/, /qualifier/i]],
        ['BCE3947', [/isolated/, /qualifier/i, /transitively/i]],
        ['BCE3948', [/isolated/, /literal/i]],
        ['BCE3949', [/isolated/, /non-`isolated` `init\(\)`/]],
        ['BCE3950', [/init/, /isolated/]],
        ['BCE3951', [/`init`/, /isolated/]],
        ['BCE3952', [/`init`/, /isolated/, /non-`isolated`/]],
        ['BCE3953', [/@strand/, /remove/i]],
        ['BCE3954', [/start/i, /isolated/]],
        // cloning module-level state in the start arg does NOT work (BCE3943); the
        // clone must be applied to a local read under lock, at the start argument
        ['BCE3955', [/clone\(\)/, /cloneReadOnly\(\)/, /BCE3943/, /lock \{ \}/, /localData\.clone\(\)/]],
        // isolated object rules
        ['BCE3956', [/private/, /final/]],
        ['BCE3957', [/lock \{ \.\.\. \}/, /self/]],
        // fires on isolated-variable initializers AND self-field assignments in isolated init
        ['BCE3958', [/literal/i, /clone\(\)/, /`self`/, /`init`/]],
        // transfer rules — the LS quick fixes are clone() / cloneReadOnly() / & readonly
        ['BCE3959', [/clone\(\)/, /cloneReadOnly\(\)/, /& readonly/]],
        ['BCE3960', [/clone\(\)/, /readonly/, /referenced inside/]],
        ['BCE3961', [/isolated/, /lock/i]],
        ['BCE3962', [/lock \{ \.\.\. \}/, /ONE isolated variable/i]],
        // BCE3963 fires only for destructuring; member/index access reports BCE3960
        ['BCE3963', [/destructuring/i, /variable name/i, /BCE3960/]],
        ['BCE3964', [/ONE isolated variable/i, /separate lock/i]],
        ['BCE3965', [/initializ/i]],
        ['BCE3966', [/simple variable/i]],
        // match guards
        ['BCE4018', [/isolated/, /readonly/i]],
        ['BCE4019', [/cloneReadOnly\(\)/, /matched value/i]],
        ['BCE4025', [/literal/i, /lock \{ \}/, /isolated/]],
        // service concurrency hints: fix is service isolation, gated on user intent.
        // The leading trigger description must discriminate the three codes —
        // BCH2003 = neither isolated, BCH2004 = service not, BCH2005 = method not.
        ['BCH2003', [/^Neither the service nor this method/, /private/, /lock \{ \}/, /isolated/, /Only make this change/i]],
        ['BCH2004', [/^This method is `isolated` but the service is not/, /private/, /Only make this change/i]],
        ['BCH2005', [/^The service is isolated but this method is not/, /isolated/, /Only make this change/i]],
    ];

    test.each(mustMention)('%s hint teaches the canonical fix', (code, patterns) => {
        const hint = DIAGNOSTIC_HINTS[code];
        expect(hint).toBeDefined();
        for (const pattern of patterns) {
            expect(hint).toMatch(pattern);
        }
    });

    test('concurrency hints never claim to be compilation errors', () => {
        for (const code of CONCURRENCY_CODES) {
            const hint = DIAGNOSTIC_HINTS[code];
            expect(hint).not.toMatch(/compilation error/i);
            // every BCH hint explains that dispatch is serialized
            expect(hint).toMatch(/serial/i);
        }
    });

    test('the three concurrency hints are pairwise distinct', () => {
        const texts = CONCURRENCY_CODES.map(code => DIAGNOSTIC_HINTS[code]);
        expect(new Set(texts).size).toBe(CONCURRENCY_CODES.length);
    });
});

// ---------------------------------------------------------------------------
// transformDiagnostics
// ---------------------------------------------------------------------------

function makeDiag(
    code: string | number | undefined,
    severity: number | undefined,
    message: string,
    line = 5,
): any {
    return {
        code,
        severity,
        message,
        range: { start: { line, character: 4 }, end: { line, character: 20 } },
    };
}

function wrap(uri: string, diags: any[]): Diagnostics {
    return { uri, diagnostics: diags } as Diagnostics;
}

describe('transformDiagnostics', () => {
    test('includes severity-1 diagnostics as errors with location prefix and hint', () => {
        const input = [wrap('file:///proj/main.bal', [
            makeDiag('BCE3962', 1, "invalid access of an 'isolated' variable outside a 'lock' statement"),
        ])];
        const { errors, concurrencyHints } = transformDiagnostics(input);

        expect(errors).toHaveLength(1);
        expect(concurrencyHints).toHaveLength(0);
        expect(errors[0].code).toBe('BCE3962');
        expect(errors[0].message).toBe(
            "[main.bal:5,4:5,20] invalid access of an 'isolated' variable outside a 'lock' statement");
        expect(errors[0].hint).toBe(DIAGNOSTIC_HINTS['BCE3962']);
    });

    test('excludes warnings and hints that are not concurrency hint codes', () => {
        const input = [wrap('file:///proj/main.bal', [
            makeDiag('BCE20406', 2, "the 'strand' annotation will be deprecated"),
            makeDiag('BCH2000', 4, 'unnecessary condition'),
        ])];
        const { errors, concurrencyHints } = transformDiagnostics(input);
        expect(errors).toHaveLength(0);
        expect(concurrencyHints).toHaveLength(0);
    });

    test('routes non-error BCH2003–BCH2005 to concurrencyHints', () => {
        const input = [wrap('file:///proj/service.bal', [
            makeDiag('BCH2004', 4, "concurrent calls will not be made to this method since the service is not an 'isolated' service"),
            makeDiag('BCH2003', 4, "concurrent calls will not be made to this method since the service and the method are not 'isolated'"),
            makeDiag('BCH2005', undefined, "concurrent calls will not be made to this method since the method is not an 'isolated' method"),
        ])];
        const { errors, concurrencyHints } = transformDiagnostics(input);

        expect(errors).toHaveLength(0);
        expect(concurrencyHints).toHaveLength(3);
        expect(concurrencyHints.map(h => h.code).sort()).toEqual(['BCH2003', 'BCH2004', 'BCH2005']);
        for (const entry of concurrencyHints) {
            expect(entry.hint).toBe(DIAGNOSTIC_HINTS[entry.code as string]);
            expect(entry.message).toContain('[service.bal:');
        }
    });

    test('a BCH code at error severity stays in errors (never downgraded to a hint)', () => {
        const input = [wrap('file:///proj/service.bal', [
            makeDiag('BCH2003', 1, 'hypothetical error-severity concurrency diagnostic'),
        ])];
        const { errors, concurrencyHints } = transformDiagnostics(input);
        expect(concurrencyHints).toHaveLength(0);
        expect(errors.map(e => e.code)).toEqual(['BCH2003']);
    });

    test('handles a diagnostic without a code (no crash, no hint, no code field)', () => {
        const input = [wrap('file:///proj/main.bal', [
            makeDiag(undefined, 1, 'some diagnostic without a code'),
        ])];
        const { errors } = transformDiagnostics(input);
        expect(errors).toHaveLength(1);
        expect(errors[0].code).toBeUndefined();
        expect(errors[0].hint).toBeUndefined();
        expect(errors[0].message).toContain('some diagnostic without a code');
    });

    test('stringifies numeric diagnostic codes', () => {
        const input = [wrap('file:///proj/main.bal', [makeDiag(1234, 1, 'numeric code diag')])];
        const { errors } = transformDiagnostics(input);
        expect(errors[0].code).toBe('1234');
    });

    test('errors without a matching hint carry no hint field', () => {
        const input = [wrap('file:///proj/main.bal', [makeDiag('BCE9999', 1, 'unknown error')])];
        const { errors } = transformDiagnostics(input);
        expect(errors[0].hint).toBeUndefined();
    });

    test('separates mixed errors and concurrency hints across files', () => {
        const input = [
            wrap('file:///proj/main.bal', [
                makeDiag('BCE3959', 1, 'invalid attempt to transfer out a value', 10),
                makeDiag('BCH2004', 4, 'concurrent calls will not be made to this method', 3),
            ]),
            wrap('file:///proj/utils.bal', [
                makeDiag('BCE3964', 1, 'cannot access more than one variable', 7),
            ]),
        ];
        const { errors, concurrencyHints } = transformDiagnostics(input);
        expect(errors.map(e => e.code)).toEqual(['BCE3959', 'BCE3964']);
        expect(concurrencyHints.map(h => h.code)).toEqual(['BCH2004']);
        expect(errors[1].message).toContain('[utils.bal:7,');
    });

    test('returns empty results for empty input', () => {
        expect(transformDiagnostics([])).toEqual({ errors: [], concurrencyHints: [] });
    });
});

describe('buildConcurrencyHintNote', () => {
    test('empty for zero hints', () => {
        expect(buildConcurrencyHintNote(0)).toBe('');
    });

    test('frames hints as non-blocking and gated on user intent', () => {
        const note = buildConcurrencyHintNote(2);
        expect(note).toContain('2 service-concurrency hint(s)');
        expect(note).toContain('NOT compilation errors');
        expect(note).toMatch(/ONLY if the user's request involves concurrency/);
    });
});
