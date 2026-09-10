/**
 * System-prompt rules for module-level variable initialization ordering.
 *
 * Copilot library testing produced compile errors ("uninitialized variable
 * 'supportTicketAgent'") when generated code declared a module-level variable
 * without an initializer and assigned it in init() in a way the dataflow
 * analyzer could not prove — or read it from a module-level initializer, which
 * is evaluated before init() runs.
 *
 * Kept in its own module (no imports) so it can be unit-tested without loading
 * the extension-host module graph. Interpolated into the system prompt by
 * getSystemPrompt() in ./prompts.ts.
 */
export const MODULE_INIT_CODING_RULES = `## Module-level initialization
- Prefer initializing module-level variables at their declaration.
- When a value can only be built inside the module \`init()\` function.
  - Declare the variable without an initializer, as \`final\`; drop \`final\` only if it is reassigned after \`init()\`.
  - Assign it in the \`init()\` function. If there is a conditinal assignement needed, Assign it in every branch of the conditional. Otherwise the compiler reports the variable as uninitialized.
`;
