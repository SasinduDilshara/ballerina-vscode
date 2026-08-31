import { Diagnostics } from '@wso2/ballerina-core';
import { checkProjectDiagnostics, isModuleNotFoundDiagsExist as resolveModuleNotFoundDiagnostics } from '../../../../rpc-managers/ai-panel/repair-utils';
import { StateMachine } from '../../../../stateMachine';
import { Uri } from 'vscode';
import { buildConcurrencyHintNote, EnrichedDiagnostic, transformDiagnostics } from './diagnostic-hints';

export type { EnrichedDiagnostic } from './diagnostic-hints';

export const DIAGNOSTICS_TOOL_NAME = "getCompilationErrors";

/**
 * Result of diagnostic checking
 */
export interface DiagnosticsCheckResult {
    diagnostics: EnrichedDiagnostic[];
    /**
     * Service-concurrency hints (BCH2003–BCH2005). Present only when the compiler
     * reports that resource/remote methods will not be dispatched concurrently.
     * These are NOT compilation errors — the code compiles.
     */
    concurrencyHints?: EnrichedDiagnostic[];
    message: string;
}


/**
 * Checks the Ballerina package for compilation errors using the language server
 *
 * This function:
 * 1. Gets the current project from the state machine
 * 2. Calls the language server to get package-level diagnostics
 * 3. Enriches diagnostics with resolving hints based on diagnostic codes
 *
 * Note: In Ballerina, diagnostics are generated at the package level, so this checks
 * the entire package/project in the current workspace.
 *
 * Deliberately queries the file:// scheme (checkProjectDiagnostics's default), not ai://
 * — tempProjectPath is the real project root, and ai:// is the frozen pre-generation
 * baseline. Querying ai:// would report diagnostics for the code as it was before this
 * generation's edits, never surfacing errors the agent's own changes introduced.
 *
 * @param updatedSourceFiles - Array of source files in the current session (not used, kept for compatibility)
 * @param updatedFileNames - Array of file names in the current session (not used, kept for compatibility)
 * @returns DiagnosticsCheckResult with enriched diagnostics
 */
export async function checkCompilationErrors(
    tempProjectPath: string
): Promise<DiagnosticsCheckResult> {
    try {
        // Get language client from state machine
        const langClient = StateMachine.langClient();

        // Get diagnostics from language server for the current project
        console.log(`[DiagnosticsUtils] Calling language server for diagnostics on ${tempProjectPath}`);
        let diagnostics: Diagnostics[] = [];
        try {
            diagnostics = await checkProjectDiagnostics(langClient, tempProjectPath);
            // HACK: When the generated code includes `import ballerinax/client.config;` (without the quoted
            // identifier), the language server returns diagnostics with the module name stripped to
            // `ballerinax/.config` — omitting "client". As a workaround, we detect this and
            // instruct the agent to use the correct quoted form `import ballerinax/'client.config;`
            // instead of attempting to resolve the dependency automatically.
            const enrichedDiagnosticsTry = transformDiagnostics(diagnostics).errors;
            const hasInvalidClientModuleImport = enrichedDiagnosticsTry.some(
                d => d.code === "BCE2003" && d.message.includes("ballerinax/.config")
            );
            if (hasInvalidClientModuleImport) {
                console.log(`[DiagnosticsUtils] Detected invalid client module import 'ballerinax/client.config'.`);
                return {
                    diagnostics: enrichedDiagnosticsTry,
                    message: `Found a module resolution error: the import 'import ballerinax/client.config;' is invalid. ` +
                        `Fix this by replacing the import statement with 'import ballerinax/'client.config;'. ` +
                        `After applying the fix, call the ${DIAGNOSTICS_TOOL_NAME} tool again to verify there are no remaining errors.`
                };
            }
        } catch (diagError) {
            // Resolve module dependencies against the live project (file:// — tempProjectPath
            // is the real workspace/project root). Using ai:// here would resolve against the
            // frozen pre-generation baseline instead, missing any import just added this turn.
            const fileUri = Uri.file(tempProjectPath).toString();
            await langClient.resolveModuleDependencies({
                documentIdentifier: {
                    uri: fileUri
                }
            });
            diagnostics = await checkProjectDiagnostics(langClient, tempProjectPath);
        }
        // Check if there are module not found diagnostics and attempt to resolve them
        const isDiagsChanged = await resolveModuleNotFoundDiagnostics(diagnostics, langClient);
        if (isDiagsChanged) {
            diagnostics = await checkProjectDiagnostics(langClient, tempProjectPath);
        }

        // Transform and enrich diagnostics with hints
        const { errors: enrichedDiagnostics, concurrencyHints } = transformDiagnostics(diagnostics);

        const errorCount = enrichedDiagnostics.length;
        const hintNote = buildConcurrencyHintNote(concurrencyHints.length);
        console.log(`[DiagnosticsUtils] Found ${errorCount} compilation error(s) and ${concurrencyHints.length} concurrency hint(s).`);

        if (errorCount === 0) {
            console.log(`[DiagnosticsUtils] No compilation errors found.`);
            return {
                diagnostics: [],
                ...(concurrencyHints.length > 0 ? { concurrencyHints } : {}),
                message: "No compilation errors found. Code compiles successfully." + hintNote,
            };
        }

        console.log(`[DiagnosticsUtils] Enriched Diagnostics:`, enrichedDiagnostics);
        return {
            diagnostics: enrichedDiagnostics,
            ...(concurrencyHints.length > 0 ? { concurrencyHints } : {}),
            message: `Found ${errorCount} compilation error(s). Review and fix the errors before proceeding.` + hintNote
        };
    } catch (error) {
        console.error("[DiagnosticsUtils] Error checking compilation errors:", error);
        return {
            diagnostics: [{
                message: "Internal error occurred while checking compilation errors."
            }],
            message: `<CRITICAL_ERROR> Failed to check compilation errors due to an internal error. Avoid try to resolve this with code changes. Acknowledge the failure, consider the task is done.
Reason: ${error instanceof Error ? error.message : 'Unknown error'}
</CRITICAL_ERROR>`,
        };
    }
}
