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
 * The selection decisions that decide what a library contributes to a prompt — which services survive, and
 * which libraries the selection model is asked about at all.
 *
 * Kept out of `function-registry` for one reason: that module reaches the language server through
 * `activator`, so importing it starts VS Code. Everything here is a pure function of its arguments, which is
 * what lets `library-selection.test.ts` pin behaviour that is otherwise only observable as a difference in
 * the rendered catalog.
 */

import {
    GetFunctionResponse,
    GetFunctionsRequest,
    MinifiedClient,
    MinifiedHandler,
    MinifiedService,
} from "./function-types";
import { FixedService, Service } from "./library-types";

/**
 * A library with fewer services than this never has them filtered.
 *
 * The saving is what scales with the count, the risk does not: dropping one of two services halves nothing
 * worth having and can still cost the reader the service they needed. Filtering earns its risk only where a
 * library declares many — `ballerinax/trigger.*` packages are the shape this exists for.
 */
export const MIN_SERVICES_TO_FILTER = 3;

export function getClientFunctionCount(clients: MinifiedClient[]): number {
    return clients.reduce((count, client) => count + client.functions.length, 0);
}

/**
 * Whether a request entry gives the selection model no choice to make.
 *
 * The model's only job is to include or exclude clients and functions. A library carrying neither — a
 * trigger package, or one that is services and types alone — has nothing for it to decide, so the call can
 * only be a no-op or a loss. Services are deliberately NOT part of the test: they are re-resolved by
 * {@link selectServices} from the library's own definitions, and a passthrough entry names none, which is
 * exactly the case that function falls back to keeping all of them.
 */
export function hasNothingToSelect(lib: GetFunctionsRequest): boolean {
    return getClientFunctionCount(lib.clients) === 0 && (lib.functions?.length ?? 0) === 0;
}

/**
 * The longest handler `doc` the request carries, in characters.
 *
 * A bound rather than a budget: the selection model needs enough of the sentence to tell one handler from
 * another, and `websocket` — 12 handlers, the corpus maximum — costs about 300 tokens at this cap. Without
 * one, a single verbose document would decide how much of the request every other library gets.
 */
export const MAX_HANDLER_DOC_CHARS = 120;

/**
 * One handler as the request states it: its name, and the first line of its documentation.
 *
 * The first line only. A handler `doc` opens with what the handler is for and continues into how to write
 * it — `kafka`'s `onConsumerRecord` runs to 157 characters, `grpc`'s to 230 — and only the opening answers
 * the question this request exists to ask.
 */
function toRequestHandler(name: string, description?: string): MinifiedHandler {
    const firstLine = description?.split("\n")[0].trim();
    if (!firstLine) {
        return { name };
    }
    return { name, doc: firstLine.slice(0, MAX_HANDLER_DOC_CHARS) };
}

/**
 * The services of one library, as the selection request states them.
 *
 * Three fields per service, and the reason each is there:
 *  - `listener` and `name` are the *identity* the response echoes back — {@link selectServices} re-resolves
 *    a selection by exactly this pair, so they are sent verbatim and never prettified;
 *  - `methods` is the *evidence*, and nothing more: the response has no counterpart for it (see
 *    `SelectedService`), so a handler cannot be selected individually. It is sent because handler names and
 *    their `doc` lines are the only thing that makes a service recognisably relevant to a query — the spec
 *    gives `serviceType` no `doc` field, so the type name is otherwise the whole story.
 *
 * A `generic` service states no methods: its handlers live in curated prose rather than in a method list,
 * and there is nothing to enumerate. Such an entry reaches the model as a listener and a name, which is why
 * {@link selectServices} refuses to filter a library declaring fewer than {@link MIN_SERVICES_TO_FILTER}
 * services — for those, a name is not enough to decide on and the whole set is kept.
 *
 * Lives here rather than in `function-registry` for the reason this module exists: it is a pure function of
 * its arguments, and it decides what the selection model is allowed to reason over — which is a selection
 * decision, not a transport detail.
 */
export function toServiceRequestEntries(services?: Service[]): MinifiedService[] | undefined {
    if (!services || services.length === 0) {
        return undefined;
    }
    return services.map((svc) => {
        const result: MinifiedService = {
            listener: svc.listener.name,
        };
        if (svc.name) {
            result.name = svc.name;
        }
        if (svc.type === "fixed") {
            const handlers = ((svc as FixedService).methods ?? [])
                .filter((method) => method && method.name)
                .map((method) => toRequestHandler(method.name, method.description));
            if (handlers.length > 0) {
                result.methods = handlers;
            }
        }
        return result;
    });
}

/**
 * Identity of one service, as both sides of the selection can state it.
 *
 * `listener` + `name` and nothing else: those are the two fields the request sends and the response echoes.
 * Two services sharing both are indistinguishable to the model, so they are kept or dropped together —
 * over-inclusion, which is the safe direction here.
 */
function serviceKey(listener: string, name?: string): string {
    return `${listener}\u0000${name ?? ""}`;
}

/**
 * The services the selection model kept, re-resolved against the library's own definitions.
 *
 * This is the half of the response that used to be requested and then discarded: the prompt asked for
 * matching services, the model spent output tokens listing them, and `toMaximizedLibrariesFromLibJson`
 * attached the library's whole set regardless. A ten-service trigger package answered a question about one
 * event with all ten, plus — through the type closure that walks them — every type all ten name.
 *
 * **Three cases fall back to the whole set, and each is a deliberate refusal to trust a filter over a
 * catalog:**
 *  - the library declares fewer than {@link MIN_SERVICES_TO_FILTER}, so there is nothing worth saving;
 *  - the response names no services at all, which is the shape every response had before this field was
 *    consumed — degrading to the previous behaviour rather than emptying the section;
 *  - the response names services but none of them resolve, which means the model answered in a shape this
 *    code cannot read. A filter that matched nothing is indistinguishable from a filter that meant nothing,
 *    and silently rendering zero services is the worse of the two readings.
 *
 * Returns `null` only for a library that declares no services, matching the field's absent form.
 */
export function selectServices(
    originalServices: Service[] | undefined,
    funcResponse: GetFunctionResponse
): Service[] | null {
    if (!originalServices || originalServices.length === 0) {
        return null;
    }
    const selected = funcResponse.services ?? [];
    if (selected.length === 0 || originalServices.length < MIN_SERVICES_TO_FILTER) {
        return originalServices;
    }

    const keep = new Set(selected.map((svc) => serviceKey(svc.listener, svc.name)));
    const filtered = originalServices.filter((svc) => keep.has(serviceKey(svc.listener?.name, svc.name)));
    if (filtered.length === 0) {
        console.warn(
            `[selectServices] ${funcResponse.name}: none of the ${selected.length} selected services matched `
            + `the library's ${originalServices.length}. Keeping all of them.`
        );
        return originalServices;
    }
    return filtered;
}
