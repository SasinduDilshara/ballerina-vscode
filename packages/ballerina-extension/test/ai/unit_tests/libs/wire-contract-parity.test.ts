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
 * Guards the **third** wire boundary: the Java model POJOs the language server serializes, against the
 * TypeScript interfaces this package deserializes them into.
 *
 * There are three hops between a resolved Ballerina package and the text a model reads, and each one drops
 * an unknown field without complaint:
 *
 *   1. draft JSON → Java POJO      — guarded by `WireContractRoundTripTest` (Gson drops unknown keys)
 *   2. Java POJO  → wire JSON      — total, by construction (Gson writes every declared field)
 *   3. wire JSON  → TS interface   — **this file** (a field absent from the interface is unreachable)
 *
 * Hop 1 had a test because it had failed twice. Hop 3 had none, and had also failed twice:
 *
 *   - `Service.testGenerationInstruction` was loaded from `test.md`, set on every service, serialized, and
 *     named explicitly by the system prompt — while being declared nowhere in TypeScript and rendered
 *     nowhere. The prompt instructed the model to honour text it never received.
 *   - `Field.optional` was reachable only through an `as any` cast in `renderRecord`, so the compiler could
 *     not have told anyone if the producer stopped sending it, and every optional record field would have
 *     silently rendered as mandatory.
 *
 * The assertion is deliberately mechanical: take every wire key a Java POJO declares, and require the
 * corresponding TypeScript interface to declare it too. It is coupled to the Java sources on purpose — a new
 * field should fail here until it is carried the whole way, which is exactly what both incidents lacked.
 *
 * A key the TS side deliberately ignores must be listed in {@link INTENTIONALLY_UNUSED} with a reason. That
 * list is the point: "we chose not to render this" and "we forgot this exists" become different states.
 */

import * as assert from "assert";
import * as path from "path";
import * as fs from "fs";

/**
 * The monorepo root, found by walking up from the working directory.
 *
 * Deliberately not `__dirname`-relative: this test spans two packages, so it is about two fixed locations in
 * the repo rather than about where this file happens to sit, and resolving from the root says that. It also
 * keeps the file runnable under both module systems — `__dirname` is undefined when Node's native TypeScript
 * type-stripping reparses a test as ESM, which is how an ad-hoc `mocha` invocation loads it.
 */
function repoRoot(): string {
    let dir = process.cwd();
    for (let depth = 0; depth < 8; depth++) {
        if (fs.existsSync(path.join(dir, "packages", "ballerina-language-server"))) {
            return dir;
        }
        dir = path.dirname(dir);
    }
    throw new Error(`could not locate the monorepo root from ${process.cwd()}`);
}

/** The copilot model package, whose POJOs are what `ModelToJsonConverter` serializes. */
const JAVA_MODEL_DIR = path.join(
    repoRoot(), "packages", "ballerina-language-server", "flow-model-generator", "modules",
    "flow-model-generator-core", "src", "main", "java", "io", "ballerina", "flowmodelgenerator",
    "core", "copilot", "model"
);

const TS_LIBS_DIR = path.join(
    repoRoot(), "packages", "ballerina-extension", "src", "features", "ai", "utils", "libs"
);

const TS_TYPES_FILE = path.join(TS_LIBS_DIR, "library-types.ts");

/**
 * Which TypeScript interface(s) receive each Java POJO.
 *
 * A Java class may be deserialized into more than one interface: `Parameter` serves both a listener/client
 * parameter (TS `Parameter`) and a handler parameter slot (TS `ParameterDef`), which declare different halves
 * of its surface because different producers populate them. The union is therefore what must cover it.
 */
const BOUNDARY: Array<{ java: string; ts: string[] }> = [
    { java: "Library.java", ts: ["Library"] },
    { java: "Service.java", ts: ["Service", "FixedService", "GenericService"] },
    { java: "ServiceRemoteFunction.java", ts: ["ServiceRemoteFunction"] },
    { java: "Parameter.java", ts: ["Parameter", "ParameterDef"] },
    { java: "Listener.java", ts: ["Listener"] },
    { java: "Return.java", ts: ["Return"] },
    { java: "Client.java", ts: ["Client"] },
    { java: "Field.java", ts: ["Field"] },
    { java: "EnumValue.java", ts: ["EnumValue"] },
    { java: "UnionValue.java", ts: ["UnionValue"] },
    { java: "Annotation.java", ts: ["Annotation"] },
    { java: "AnnotationAttachment.java", ts: ["AnnotationAttachment"] },
    { java: "ServiceAnnotationRef.java", ts: ["AnnotationRequirement"] },
    { java: "ServiceIdentifier.java", ts: ["ServiceIdentifier"] },
    { java: "ServiceConstraint.java", ts: ["ServiceConstraint"] },
    { java: "ConstraintSubject.java", ts: ["ConstraintSubject"] },
    { java: "ParamBinding.java", ts: ["ParamBinding"] },
    { java: "TypedescVariant.java", ts: ["TypedescVariant"] },
    { java: "BindingShape.java", ts: ["BindingShape"] },
    { java: "PlatformDependency.java", ts: ["PlatformDependency"] },
    { java: "NativeLibrary.java", ts: ["NativeLibrary"] },
    { java: "RequiredImport.java", ts: ["RequiredImport"] },
    { java: "TypeLink.java", ts: ["Link"] },
];

/**
 * Wire keys the TypeScript side knowingly does not declare, and why.
 *
 * Every entry is a decision. An undeclared key that is NOT here is a defect, because the only way to reach it
 * from TypeScript is a cast — which is how `Field.optional` stayed invisible.
 */
const INTENTIONALLY_UNUSED: Record<string, string> = {
    // Empty, and that is the current truth: every wire key the language server produces for these types is
    // declared on the consumer side. Keyed `"<Pojo>.java:<wireKey>"` when an entry is needed.
};

/** Every wire key a Java POJO declares, honouring `@SerializedName`. */
function javaWireKeys(file: string): string[] {
    const source = fs.readFileSync(path.join(JAVA_MODEL_DIR, file), "utf-8");
    const keys: string[] = [];
    let alias: string | null = null;
    for (const line of source.split("\n")) {
        const serialized = /@SerializedName\("([^"]+)"\)/.exec(line);
        if (serialized) {
            alias = serialized[1];
            continue;
        }
        // `private <type> <name>;` / `= ...;`. Static and final constants are not wire fields.
        const field = /^\s*private\s+(?!static)(?:final\s+)?[\w<>,\[\].?\s]+?\s+(\w+)\s*(?:=|;)/.exec(line);
        if (field) {
            keys.push(alias ?? field[1]);
            alias = null;
        }
    }
    return keys;
}

/** Every property each TypeScript interface declares, following `extends`. */
function tsInterfaceProps(): Map<string, string[]> {
    const source = fs.readFileSync(TS_TYPES_FILE, "utf-8");
    const own = new Map<string, { parent?: string; props: string[] }>();
    const pattern = /export interface (\w+)(?:\s+extends\s+(\w+))?\s*\{([\s\S]*?)\n\}/g;
    let match: RegExpExecArray | null;
    while ((match = pattern.exec(source)) !== null) {
        const props = Array.from(match[3].matchAll(/^ {4}(\w+)\??\s*:/gm)).map((m) => m[1]);
        own.set(match[1], { parent: match[2], props });
    }
    const resolved = new Map<string, string[]>();
    for (const name of own.keys()) {
        const props: string[] = [];
        let cursor: string | undefined = name;
        while (cursor && own.has(cursor)) {
            const entry = own.get(cursor)!;
            props.push(...entry.props);
            cursor = entry.parent;
        }
        resolved.set(name, props);
    }
    return resolved;
}

suite("wire contract parity — Java model POJOs vs TypeScript interfaces", () => {
    let tsProps: Map<string, string[]>;

    suiteSetup(() => {
        assert.ok(fs.existsSync(JAVA_MODEL_DIR),
            `Java model directory not found at ${JAVA_MODEL_DIR}. If the language server moved, update the `
            + `path — do not delete this test, or hop 3 of the wire contract goes unguarded again.`);
        tsProps = tsInterfaceProps();
    });

    test("the boundary table names a TypeScript interface that actually exists", () => {
        for (const { java, ts } of BOUNDARY) {
            assert.ok(fs.existsSync(path.join(JAVA_MODEL_DIR, java)), `missing Java POJO: ${java}`);
            for (const name of ts) {
                assert.ok(tsProps.has(name),
                    `${java} is mapped to TS interface ${name}, which library-types.ts does not declare`);
            }
        }
    });

    test("every wire key a Java POJO declares is declared by its TypeScript interface", () => {
        const missing: string[] = [];
        for (const { java, ts } of BOUNDARY) {
            const declared = new Set(ts.flatMap((name) => tsProps.get(name) ?? []));
            for (const key of javaWireKeys(java)) {
                if (declared.has(key) || INTENTIONALLY_UNUSED[`${java}:${key}`]) {
                    continue;
                }
                missing.push(`${java} -> ${ts.join("|")}: "${key}"`);
            }
        }
        assert.deepStrictEqual(missing, [],
            "These wire keys are produced by the language server and declared by no TypeScript interface, so "
            + "they are unreachable without a cast — the exact shape of the two incidents this test exists "
            + "for. Declare them (and render them, if they carry content the model needs), or add them to "
            + "INTENTIONALLY_UNUSED with a reason.\n  " + missing.join("\n  "));
    });

    test("the sweep is not vacuous", () => {
        // A guard that extracts zero keys passes for a completely broken consumer, which would be worse than
        // having no guard at all. So the extractor is checked against keys that must be found.
        for (const { java } of BOUNDARY) {
            assert.ok(javaWireKeys(java).length > 0, `extracted no wire keys at all from ${java}`);
        }
        const service = javaWireKeys("Service.java");
        assert.ok(service.includes("testGenerationInstruction") && service.includes("methods")
            && service.includes("listener"), `Service.java keys look wrong: ${service.join(", ")}`);
        // `@SerializedName` aliases must be read as the WIRE name, not the field name: `deprecationNote` is
        // serialized as `deprecated`, and reading the field name would look for a key that never exists.
        assert.ok(service.includes("deprecated") && !service.includes("deprecationNote"),
            `@SerializedName was not honoured: ${service.join(", ")}`);
        const handler = javaWireKeys("ServiceRemoteFunction.java");
        assert.ok(handler.includes("pathValues") && handler.includes("accessorValues"),
            `ServiceRemoteFunction.java keys look wrong: ${handler.join(", ")}`);
    });

    test("the two fields this test was written for are declared", () => {
        // Named individually rather than left to the sweep: a future refactor that weakens the sweep should
        // still fail here, because these two are the evidence that the boundary was unguarded.
        assert.ok((tsProps.get("Service") ?? []).includes("testGenerationInstruction"),
            "the system prompt instructs the model to respect this field, so it must reach the model");
        assert.ok((tsProps.get("Field") ?? []).includes("optional"),
            "read through an `as any` cast until it was declared; a cast hides a producer that stops sending");
    });

    test("no interface reachable from the boundary is read through an `as any` cast in the renderer", () => {
        // The cast is what let an undeclared field be used without the compiler noticing, so its absence is
        // part of the guarantee rather than a style preference.
        const renderer = fs.readFileSync(path.join(TS_LIBS_DIR, "to-syntax-string.ts"), "utf-8");
        const casts = renderer.split("\n")
            .map((line, index) => ({ line, index: index + 1 }))
            .filter(({ line }) => /\bas any\b/.test(line));
        assert.deepStrictEqual(casts.map(({ index, line }) => `${index}: ${line.trim()}`), [],
            "an `as any` cast in the renderer bypasses this test's guarantee");
    });
});
