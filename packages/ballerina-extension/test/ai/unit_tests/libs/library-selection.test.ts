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

import * as assert from "assert";
import {
    MAX_HANDLER_DOC_CHARS,
    MIN_SERVICES_TO_FILTER,
    hasNothingToSelect,
    selectServices,
    toServiceRequestEntries,
} from "../../../../src/features/ai/utils/libs/library-selection";
import { Service } from "../../../../src/features/ai/utils/libs/library-types";
import { GetFunctionResponse, GetFunctionsRequest } from "../../../../src/features/ai/utils/libs/function-types";

function service(listener: string, name?: string): Service {
    return {
        type: "fixed",
        listener: { name: listener, parameters: [] },
        ...(name ? { name } : {}),
    } as Service;
}

/** A library with `count` distinct services, named `S0`…`S{count-1}` on one listener. */
function services(count: number, listener = "trigger:Listener"): Service[] {
    return Array.from({ length: count }, (_, i) => service(listener, `S${i}`));
}

function response(selected?: { listener: string; name?: string }[]): GetFunctionResponse {
    return { name: "ballerinax/trigger.example", ...(selected ? { services: selected } : {}) };
}

function request(partial: Partial<GetFunctionsRequest>): GetFunctionsRequest {
    return { name: "lib", description: "", clients: [], ...partial } as GetFunctionsRequest;
}

suite("library selection — selectServices", () => {
    test("keeps only the services the model named", () => {
        const all = services(5);
        const kept = selectServices(all, response([
            { listener: "trigger:Listener", name: "S1" },
            { listener: "trigger:Listener", name: "S3" },
        ]));
        assert.deepStrictEqual(kept?.map((s) => s.name), ["S1", "S3"]);
    });

    test("a library declaring no services yields null, matching the absent field", () => {
        assert.strictEqual(selectServices(undefined, response([])), null);
        assert.strictEqual(selectServices([], response([])), null);
    });

    // The regression this whole change exists to prevent: before it, the response was ignored and every
    // service was attached. After it, an unanswered response must still attach every service — a filter
    // that silently empties the section is worse than the over-inclusion it replaced.
    test("an absent services field falls back to the whole set", () => {
        const all = services(5);
        assert.strictEqual(selectServices(all, response()), all);
    });

    test("an empty services array is treated as unanswered, not as 'none relevant'", () => {
        const all = services(5);
        assert.strictEqual(selectServices(all, response([])), all);
    });

    test("a selection that resolves to nothing falls back rather than emptying the section", () => {
        const all = services(5);
        const kept = selectServices(all, response([{ listener: "other:Listener", name: "Nope" }]));
        assert.strictEqual(kept, all);
    });

    test("below the threshold, nothing is filtered even when the model answers", () => {
        const all = services(MIN_SERVICES_TO_FILTER - 1);
        const kept = selectServices(all, response([{ listener: "trigger:Listener", name: "S0" }]));
        assert.strictEqual(kept, all, "a small service set is never narrowed");
    });

    test("at the threshold, filtering applies", () => {
        const all = services(MIN_SERVICES_TO_FILTER);
        const kept = selectServices(all, response([{ listener: "trigger:Listener", name: "S0" }]));
        assert.deepStrictEqual(kept?.map((s) => s.name), ["S0"]);
    });

    test("identity is listener + name, so the same name on another listener does not match", () => {
        const all = [
            service("kafka:Listener", "Service"),
            service("rabbitmq:Listener", "Service"),
            service("mqtt:Listener", "Service"),
        ];
        const kept = selectServices(all, response([{ listener: "rabbitmq:Listener", name: "Service" }]));
        assert.deepStrictEqual(kept?.map((s) => s.listener.name), ["rabbitmq:Listener"]);
    });

    test("unnamed services are matched by listener alone", () => {
        const all = [service("a:Listener"), service("b:Listener"), service("c:Listener")];
        const kept = selectServices(all, response([{ listener: "b:Listener" }]));
        assert.deepStrictEqual(kept?.map((s) => s.listener.name), ["b:Listener"]);
    });

    // Two services the model cannot tell apart are kept or dropped together. Over-inclusion is the safe
    // direction: the alternative is dropping one of an indistinguishable pair at random.
    test("services sharing listener and name survive together", () => {
        const all = [service("a:Listener", "S"), service("a:Listener", "S"), service("b:Listener", "T")];
        const kept = selectServices(all, response([{ listener: "a:Listener", name: "S" }]));
        assert.strictEqual(kept?.length, 2);
    });

    test("the original array is never mutated", () => {
        const all = services(5);
        const before = all.slice();
        selectServices(all, response([{ listener: "trigger:Listener", name: "S1" }]));
        assert.deepStrictEqual(all, before);
    });
});

suite("library selection — hasNothingToSelect", () => {
    test("a services-only library has nothing to select", () => {
        assert.strictEqual(hasNothingToSelect(request({ clients: [], functions: [] })), true);
    });

    test("a library with no clients and no functions field at all has nothing to select", () => {
        assert.strictEqual(hasNothingToSelect(request({ clients: [] })), true);
    });

    test("a client with zero functions still offers nothing to choose between", () => {
        assert.strictEqual(
            hasNothingToSelect(request({ clients: [{ name: "Client", functions: [] }] })),
            true
        );
    });

    test("one client function is enough to make selection meaningful", () => {
        assert.strictEqual(
            hasNothingToSelect(request({
                clients: [{ name: "Client", functions: [{ name: "get", parameters: [], returnType: "error?" }] }],
            })),
            false
        );
    });

    test("one module-level function is enough on its own", () => {
        assert.strictEqual(
            hasNothingToSelect(request({
                clients: [],
                functions: [{ name: "parse", parameters: [], returnType: "string" }],
            })),
            false
        );
    });
});

/** A fixed service whose handlers are given as `[name, doc]` pairs. */
function fixedService(
    listener: string,
    name: string,
    methods: [string, string?][]
): Service {
    return {
        type: "fixed",
        listener: { name: listener, parameters: [] },
        name,
        methods: methods.map(([methodName, description]) => ({
            name: methodName,
            type: "remote",
            parameters: [],
            return: { type: { name: "error?" } },
            ...(description !== undefined ? { description } : {}),
        })),
    } as Service;
}

suite("library selection — toServiceRequestEntries", () => {
    test("sends the identity pair the response echoes back, verbatim", () => {
        const entries = toServiceRequestEntries([
            fixedService("kafka:Listener", "Service", [["onConsumerRecord"]]),
        ]);
        assert.strictEqual(entries?.length, 1);
        assert.strictEqual(entries?.[0].listener, "kafka:Listener");
        assert.strictEqual(entries?.[0].name, "Service");
    });

    test("carries each handler's first doc line — the only prose a service type has", () => {
        const entries = toServiceRequestEntries([
            fixedService("kafka:Listener", "Service", [
                ["onConsumerRecord", "Invoked with each batch of records polled from the subscribed topics.\nThe listener polls on its configured interval."],
                ["onError", "Invoked when polling fails."],
            ]),
        ]);
        assert.deepStrictEqual(entries?.[0].methods, [
            { name: "onConsumerRecord", doc: "Invoked with each batch of records polled from the subscribed topics." },
            { name: "onError", doc: "Invoked when polling fails." },
        ]);
    });

    test("caps a doc so one verbose document cannot dominate the request", () => {
        const long = "x".repeat(MAX_HANDLER_DOC_CHARS + 50);
        const entries = toServiceRequestEntries([
            fixedService("ftp:Listener", "Service", [["onFileChange", long]]),
        ]);
        assert.strictEqual(entries?.[0].methods?.[0].doc?.length, MAX_HANDLER_DOC_CHARS);
    });

    test("omits `doc` entirely for an undocumented handler rather than sending an empty one", () => {
        // trigger.github's shape: handlers introspected from a concrete type that carries no doc comments.
        const entries = toServiceRequestEntries([
            fixedService("github:Listener", "IssuesService", [["onOpened"], ["onClosed", "   "]]),
        ]);
        assert.deepStrictEqual(entries?.[0].methods, [{ name: "onOpened" }, { name: "onClosed" }]);
    });

    test("a generic service states no methods, so only its identity is sent", () => {
        const generic = {
            type: "generic",
            listener: { name: "http:Listener", parameters: [] },
            name: "Service",
            instructions: "…",
        } as Service;
        assert.deepStrictEqual(toServiceRequestEntries([generic]), [
            { listener: "http:Listener", name: "Service" },
        ]);
    });

    test("omits the `methods` key when a fixed service declares none", () => {
        // mcp's wildcard (addMode: many) service types reach the request with no methods at all.
        const entries = toServiceRequestEntries([
            fixedService("mcp:StreamableHttpListener", "Service", []),
        ]);
        assert.deepStrictEqual(entries, [
            { listener: "mcp:StreamableHttpListener", name: "Service" },
        ]);
    });

    test("a nameless service omits `name`, matching the key selectServices builds", () => {
        const entries = toServiceRequestEntries([
            { type: "fixed", listener: { name: "x:Listener", parameters: [] } } as Service,
        ]);
        assert.deepStrictEqual(entries, [{ listener: "x:Listener" }]);
    });

    test("a library declaring no services sends the field absent, not empty", () => {
        assert.strictEqual(toServiceRequestEntries(undefined), undefined);
        assert.strictEqual(toServiceRequestEntries([]), undefined);
    });

    test("the entries a request states are re-resolvable by selectServices", () => {
        const all = [
            fixedService("trigger:Listener", "S0", [["onA", "First."]]),
            fixedService("trigger:Listener", "S1", [["onB", "Second."]]),
            fixedService("trigger:Listener", "S2", [["onC", "Third."]]),
        ];
        const entries = toServiceRequestEntries(all)!;
        // The model echoes one entry back exactly as it was sent.
        const kept = selectServices(all, response([
            { listener: entries[1].listener, name: entries[1].name },
        ]));
        assert.deepStrictEqual(kept?.map((s) => s.name), ["S1"]);
    });
});
