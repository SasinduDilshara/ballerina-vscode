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

import {
    Library,
    TypeDefinition,
    RecordTypeDefinition,
    EnumTypeDefinition,
    UnionTypeDefinition,
    ConstantTypeDefinition,
    ClassTypeDefinition,
    Client,
    RemoteFunction,
    ResourceFunction,
    Field,
    Type,
    Link,
    Parameter,
    GenericService,
    FixedService,
    Service,
    ParameterDef,
    PathParameter,
    Annotation,
    AnnotationAttachment,
} from "./library-types";

/**
 * Dual attachment points, mapped to the token an annotation declaration's `on` clause uses.
 * These are written bare, on a plain `public annotation ...` declaration.
 *
 * <p>The compiler's `OBJECT` point is deliberately absent: current Ballerina has no source form
 * for it (neither `on object` nor `on object type` parses), so entries carrying it are dropped
 * rather than rendered as text that does not compile.</p>
 */
const DUAL_ATTACHMENT_POINT_LABELS: Record<string, string> = {
    TYPE: "type",
    CLASS: "class",
    FUNCTION: "function",
    OBJECT_METHOD: "object function",
    RESOURCE: "service remote function",
    PARAMETER: "parameter",
    RETURN: "return",
    SERVICE: "service",
    FIELD: "field",
    OBJECT_FIELD: "object field",
    RECORD_FIELD: "record field",
};

/**
 * Source-only attachment points. These carry a `source` prefix, and any declaration that uses one
 * must additionally be a `const` declaration — `public const annotation X on source external;`.
 */
const SOURCE_ONLY_ATTACHMENT_POINT_LABELS: Record<string, string> = {
    ANNOTATION: "source annotation",
    EXTERNAL: "source external",
    VAR: "source var",
    CONST: "source const",
    LISTENER: "source listener",
    WORKER: "source worker",
};

interface AttachmentPointLabel {
    label: string;
    sourceOnly: boolean;
}

/**
 * Resolves a compiler attachment point to its declaration token, or `null` when the point has no
 * source form.
 */
function resolveAttachmentPoint(attachmentPoint: string): AttachmentPointLabel | null {
    const dual = DUAL_ATTACHMENT_POINT_LABELS[attachmentPoint];
    if (dual) {
        return { label: dual, sourceOnly: false };
    }
    const sourceOnly = SOURCE_ONLY_ATTACHMENT_POINT_LABELS[attachmentPoint];
    if (sourceOnly) {
        return { label: sourceOnly, sourceOnly: true };
    }
    return null;
}

/**
 * Derives a module prefix from a library name.
 * Rule: split on `/` and `.`, take the last segment.
 * e.g., "ballerina/http" -> "http", "ballerinax/docusign.dsesign" -> "dsesign"
 */
export function deriveModulePrefix(libraryName: string): string {
    const parts = libraryName.split(/[/.]/);
    return parts[parts.length - 1];
}

interface ExternalLinkInfo {
    recordName: string;
    libraryName: string;
    modulePrefix: string;
}

/**
 * Collects external link info from a Type's links array.
 */
function collectExternalLinks(type: Type): ExternalLinkInfo[] {
    if (!type.links) {
        return [];
    }
    return type.links
        .filter((link): link is Link & { libraryName: string } =>
            link.category === "external" && !!link.libraryName
        )
        .map((link) => ({
            recordName: link.recordName,
            libraryName: link.libraryName,
            modulePrefix: deriveModulePrefix(link.libraryName),
        }));
}

/**
 * Applies module prefix to type name for each external link using word-boundary-aware replacement.
 */
function applyPrefixToTypeName(typeName: string, externalLinks: ExternalLinkInfo[]): string {
    let result = typeName;
    for (const link of externalLinks) {
        const regex = new RegExp(`\\b${escapeRegExp(link.recordName)}\\b`, "g");
        result = result.replace(regex, `${link.modulePrefix}:${link.recordName}`);
    }
    return result;
}

function escapeRegExp(str: string): string {
    return str.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

/**
 * Builds the "// Special Agent Note: ..." comment for external links.
 * Groups record names by library name.
 */
function buildSpecialAgentNote(externalLinks: ExternalLinkInfo[]): string {
    if (externalLinks.length === 0) {
        return "";
    }

    const grouped = new Map<string, string[]>();
    for (const link of externalLinks) {
        if (!grouped.has(link.libraryName)) {
            grouped.set(link.libraryName, []);
        }
        grouped.get(link.libraryName)!.push(link.recordName);
    }

    const parts: string[] = [];
    for (const [libName, recordNames] of grouped) {
        parts.push(`${recordNames.join(", ")} FROM ${libName} package`);
    }

    return ` // Special Agent Note: ${parts.join(", ")}`;
}

/**
 * Renders a single annotation attachment as `@[prefix:]Name [value]`.
 * The module prefix is derived from the attachment's `module` (e.g. "ballerina/http" -> "http");
 * when absent, the annotation belongs to the current library and is rendered bare.
 */
function renderAttachmentName(annotation: AnnotationAttachment): string {
    const prefix = annotation.module ? deriveModulePrefix(annotation.module) : "";
    const qualifiedName = prefix ? `${prefix}:${annotation.name}` : annotation.name;
    return annotation.value ? `@${qualifiedName} ${annotation.value}` : `@${qualifiedName}`;
}

/**
 * Renders annotation attachments as one line each, prefixed with `indent`.
 *
 * A `required` binding is marked, because whether an annotation *must* be written is stated only by
 * the connector's authoring metadata — it cannot be recovered from the annotation declaration or
 * from any symbol. Unmarked bindings are optional.
 */
function renderAttachmentLines(annotations: AnnotationAttachment[] | undefined, indent: string): string[] {
    if (!annotations || annotations.length === 0) {
        return [];
    }
    return annotations.map((annotation) =>
        `${indent}${renderAttachmentName(annotation)}${annotation.required ? " // required" : ""}`);
}

/**
 * Renders annotation attachments as a block (lines + trailing newline) for string-concatenation
 * renderers. Returns "" when there are none.
 */
function renderAttachmentBlock(annotations: AnnotationAttachment[] | undefined, indent: string): string {
    const lines = renderAttachmentLines(annotations, indent);
    return lines.length > 0 ? lines.join("\n") + "\n" : "";
}

/**
 * Renders annotation attachments inline (space-separated, trailing space) for a parameter
 * declaration. Returns "" when there are none.
 */
function renderInlineAttachments(annotations: AnnotationAttachment[] | undefined): string {
    if (!annotations || annotations.length === 0) {
        return "";
    }
    return annotations.map(renderAttachmentName).join(" ") + " ";
}

/**
 * Renders a description as `#` comment lines.
 */
function renderDescription(description: string | undefined): string {
    if (!description || description.trim() === "") {
        return "";
    }
    return description
        .split("\n")
        .map((line) => `# ${line}`)
        .join("\n") + "\n";
}

/**
 * Renders a record type definition to Ballerina syntax.
 */
function renderRecord(typeDef: RecordTypeDefinition): string {
    const lines: string[] = [];
    lines.push(renderDescription(typeDef.description));
    if (typeDef.isDeprecated) {
        lines.push("@deprecated");
    }
    lines.push(...renderAttachmentLines(typeDef.annotations, ""));
    lines.push(`type ${typeDef.name} record {`);

    for (const field of typeDef.fields) {
        const externalLinks = collectExternalLinks(field.type);
        const typeName = applyPrefixToTypeName(field.type.name, externalLinks);
        const optional = (field as any).optional ? "?" : "";
        const defaultVal = field.default !== undefined ? ` = ${field.default}` : "";
        const fieldDesc = field.description ? `    # ${field.description}\n` : "";
        const fieldDeprecated = field.isDeprecated ? "    @deprecated\n" : "";
        const fieldAnnotations = renderAttachmentBlock(field.annotations, "    ");
        const agentNote = buildSpecialAgentNote(externalLinks);
        lines.push(`${fieldDesc}${fieldDeprecated}${fieldAnnotations}    ${typeName} ${field.name}${optional}${defaultVal};${agentNote}`);
    }

    lines.push("};");
    return lines.join("\n");
}

function renderDeprecation(isDeprecated: boolean | undefined): string {
    return isDeprecated ? "@deprecated\n" : "";
}

/**
 * Renders an enum type definition to Ballerina syntax.
 */
function renderEnum(typeDef: EnumTypeDefinition): string {
    const lines: string[] = [];
    lines.push(renderDescription(typeDef.description));
    if (typeDef.isDeprecated) {
        lines.push("@deprecated\n");
    }
    lines.push(renderAttachmentBlock(typeDef.annotations, ""));
    const members = typeDef.members.map((m) => m.name).join(",\n    ");
    lines.push(`enum ${typeDef.name} {\n    ${members}\n}`);
    return lines.join("");
}

/**
 * Renders a union type definition to Ballerina syntax.
 */
function renderUnion(typeDef: UnionTypeDefinition): string {
    const desc = renderDescription(typeDef.description);
    const dep = renderDeprecation(typeDef.isDeprecated);
    const ann = renderAttachmentBlock(typeDef.annotations, "");
    if (!typeDef.members || typeDef.members.length === 0) {
        return `${desc}${dep}${ann}type ${typeDef.name};`;
    }
    const members = typeDef.members.map((m) => m.name).join("|");
    return `${desc}${dep}${ann}type ${typeDef.name} ${members};`;
}

/**
 * Renders a constant type definition to Ballerina syntax.
 */
function renderConstant(typeDef: ConstantTypeDefinition): string {
    const desc = renderDescription(typeDef.description);
    const dep = renderDeprecation(typeDef.isDeprecated);
    const ann = renderAttachmentBlock(typeDef.annotations, "");
    const value = typeDef.varType.name === "string" ? `"${typeDef.value}"` : typeDef.value;
    return `${desc}${dep}${ann}const ${typeDef.varType.name} ${typeDef.name} = ${value};`;
}

/**
 * Renders a class type definition to Ballerina syntax.
 */
function renderClass(typeDef: ClassTypeDefinition): string {
    const desc = renderDescription(typeDef.description);
    const dep = renderDeprecation(typeDef.isDeprecated);
    const ann = renderAttachmentBlock(typeDef.annotations, "");
    return `${desc}${dep}${ann}class ${typeDef.name} {\n}`;
}

/**
 * Renders a type definition to Ballerina syntax.
 */
function renderTypeDef(typeDef: TypeDefinition): string {
    switch (typeDef.type) {
        case "Record":
            return renderRecord(typeDef as RecordTypeDefinition);
        case "Enum":
            return renderEnum(typeDef as EnumTypeDefinition);
        case "Union":
            return renderUnion(typeDef as UnionTypeDefinition);
        case "Constant":
            return renderConstant(typeDef as ConstantTypeDefinition);
        case "Class":
            return renderClass(typeDef as ClassTypeDefinition);
        default:
            return `// Unknown type: ${typeDef.name}`;
    }
}

/**
 * Collects all external links from parameters and return type.
 */
function collectFunctionExternalLinks(params: Parameter[], returnType?: Type): ExternalLinkInfo[] {
    const links: ExternalLinkInfo[] = [];
    for (const param of params) {
        links.push(...collectExternalLinks(param.type));
    }
    if (returnType) {
        links.push(...collectExternalLinks(returnType));
    }
    // Deduplicate by recordName + libraryName
    const seen = new Set<string>();
    return links.filter((l) => {
        const key = `${l.recordName}::${l.libraryName}`;
        if (seen.has(key)) {
            return false;
        }
        seen.add(key);
        return true;
    });
}

/**
 * Renders a parameter (for functions).
 */
function renderParam(param: Parameter): string {
    const externalLinks = collectExternalLinks(param.type);
    const typeName = applyPrefixToTypeName(param.type.name, externalLinks);
    const optional = (param as any).optional;
    const defaultVal = (param as any).default !== undefined ? ` = ${(param as any).default}` : "";
    const annotations = renderInlineAttachments(param.annotations);
    return `${annotations}${typeName} ${param.name}${defaultVal}`;
}

/**
 * Renders a constructor function.
 */
function renderConstructor(func: RemoteFunction): string {
    const allExternalLinks = collectFunctionExternalLinks(func.parameters, func.return?.type);
    const params = func.parameters.map(renderParam).join(", ");
    const returnStr = func.return?.type ? ` returns ${applyPrefixToTypeName(func.return.type.name, allExternalLinks)}` : "";
    const agentNote = buildSpecialAgentNote(allExternalLinks);
    const anns = renderAttachmentBlock(func.annotations, "    ");
    return `${anns}    function init(${params})${returnStr};${agentNote}`;
}

/**
 * Renders a remote function.
 */
function renderRemoteFunction(func: RemoteFunction, indent: string = "    "): string {
    const allExternalLinks = collectFunctionExternalLinks(func.parameters, func.return?.type);
    const desc = func.description ? `${indent}# ${func.description.split("\n").join(`\n${indent}# `)}\n` : "";
    const dep = func.isDeprecated ? `${indent}@deprecated\n` : "";
    const anns = renderAttachmentBlock(func.annotations, indent);
    const params = func.parameters.map(renderParam).join(", ");
    const returnStr = func.return?.type ? ` returns ${applyPrefixToTypeName(func.return.type.name, allExternalLinks)}` : "";
    const agentNote = buildSpecialAgentNote(allExternalLinks);
    return `${desc}${dep}${anns}${indent}remote function ${func.name}(${params})${returnStr};${agentNote}`;
}

/**
 * Renders a resource function.
 */
function renderResourceFunction(func: ResourceFunction, indent: string = "    "): string {
    const allExternalLinks = collectFunctionExternalLinks(func.parameters, func.return?.type);
    const desc = func.description ? `${indent}# ${func.description.split("\n").join(`\n${indent}# `)}\n` : "";
    const dep = func.isDeprecated ? `${indent}@deprecated\n` : "";
    const anns = renderAttachmentBlock(func.annotations, indent);

    // Build path string
    const pathSegments = func.paths.map((p) => {
        if (typeof p === "string") {
            return p;
        }
        return `[${p.type} ${p.name}]`;
    });
    const pathStr = pathSegments.join("/");

    // Exclude parameters that appear in paths
    const pathParamNames = new Set(
        func.paths
            .filter((p): p is PathParameter => typeof p !== "string")
            .map((p) => p.name)
    );
    const nonPathParams = func.parameters.filter((p) => !pathParamNames.has(p.name));
    const params = nonPathParams.map(renderParam).join(", ");

    const returnStr = func.return?.type ? ` returns ${applyPrefixToTypeName(func.return.type.name, allExternalLinks)}` : "";
    const agentNote = buildSpecialAgentNote(allExternalLinks);
    return `${desc}${dep}${anns}${indent}resource function ${func.accessor} ${pathStr}(${params})${returnStr};${agentNote}`;
}

/**
 * Renders a client to Ballerina syntax.
 */
function renderClient(client: Client): string {
    const lines: string[] = [];
    const desc = client.description ? renderDescription(client.description) : "";
    const dep = client.isDeprecated ? "@deprecated\n" : "";
    const anns = renderAttachmentBlock(client.annotations, "");
    lines.push(`${desc}${dep}${anns}client class ${client.name} {`);

    for (const func of client.functions) {
        if ("type" in func && func.type === "Constructor") {
            lines.push(renderConstructor(func as RemoteFunction));
        } else if ("accessor" in func) {
            lines.push("");
            lines.push(renderResourceFunction(func as ResourceFunction));
        } else {
            lines.push("");
            lines.push(renderRemoteFunction(func as RemoteFunction));
        }
    }

    lines.push("}");
    return lines.join("\n");
}

/**
 * Renders a standalone (normal) function to Ballerina syntax.
 * Includes `# + param` and `# + return` documentation.
 */
function renderStandaloneFunction(func: RemoteFunction): string {
    const allExternalLinks = collectFunctionExternalLinks(func.parameters, func.return?.type);
    const lines: string[] = [];

    // Description
    if (func.description) {
        const descLines = func.description.split("\n").map((l) => `# ${l}`);
        lines.push(...descLines);
    }

    // Parameter docs
    for (const param of func.parameters) {
        if (param.description) {
            lines.push(`# + ${param.name} - ${param.description}`);
        }
    }

    // Return doc
    if (func.return?.description) {
        lines.push(`# + return - ${func.return.description}`);
    }

    if (func.isDeprecated) {
        lines.push("@deprecated");
    }

    lines.push(...renderAttachmentLines(func.annotations, ""));

    const params = func.parameters.map(renderParam).join(", ");
    const returnStr = func.return?.type ? ` returns ${applyPrefixToTypeName(func.return.type.name, allExternalLinks)}` : "";
    const agentNote = buildSpecialAgentNote(allExternalLinks);
    lines.push(`function ${func.name}(${params})${returnStr};${agentNote}`);

    return lines.join("\n");
}

/**
 * Renders a ParameterDef (used in fixed service methods).
 * Parameter-point annotation bindings are written inline, ahead of the type, exactly as they are
 * written in source.
 */
function renderParamDef(param: ParameterDef & { name?: string }): string {
    const annotations = renderInlineAttachments(param.annotations);
    return `${annotations}${param.type.name}${param.name ? " " + param.name : ""}`;
}

/**
 * Renders a generic service.
 */
function renderGenericService(service: GenericService): string {
    const lines: string[] = [];
    const listenerParams = service.listener.parameters.map(
        (p) => `${p.type.name} ${p.name}`
    ).join(", ");
    lines.push(`// --- Service (generic) ---`);
    if (service.name) {
        lines.push(`// Service Type: ${service.name}`);
    }
    if (service.isDeprecated) {
        lines.push(`// Deprecated`);
    }
    lines.push(`// Listener: ${service.listener.name}(${listenerParams})`);
    lines.push(`// Instructions:`);
    if (service.instructions) {
        lines.push(service.instructions);
    }
    return lines.join("\n");
}

/**
 * Derives the module alias from a listener name like `"kafka:Listener"` → `"kafka"`.
 * Returns null when the listener name lacks a `:` separator so callers can fall back
 * to the unprefixed `service on new ...` form.
 */
function deriveListenerAlias(listenerName: string): string | null {
    const idx = listenerName.indexOf(":");
    return idx > 0 ? listenerName.substring(0, idx) : null;
}

/**
 * Renders a fixed service.
 */
function renderFixedService(service: FixedService): string {
    const lines: string[] = [];
    const listenerParams = service.listener.parameters.map(
        (p) => `${p.type.name} ${p.name}${(p as any).default !== undefined ? ` = ${(p as any).default}` : ""}`
    ).join(", ");

    if (service.isDeprecated) {
        lines.push("@deprecated");
    }

    const alias = deriveListenerAlias(service.listener.name);
    const serviceTypePrefix = service.name && alias
        ? `${alias}:${service.name} `
        : "";
    // Service-point annotation bindings precede the `service` keyword, as in source.
    lines.push(...renderAttachmentLines(service.annotations, ""));
    lines.push(`service ${serviceTypePrefix}on new ${service.listener.name}(${listenerParams}) {`);

    for (const method of service.methods ?? []) {
        const desc = method.description ? `    # ${method.description}\n` : "";
        const dep = method.isDeprecated ? "    @deprecated\n" : "";
        const anns = renderAttachmentBlock(method.annotations, "    ");
        const params = (method.parameters ?? [])
            .map((p) => renderParamDef(p as ParameterDef & { name?: string }))
            .join(", ");
        const returnStr = method.return?.type ? ` returns ${method.return.type.name}` : "";
        const notes: string[] = [];
        if (method.optional) {
            notes.push("optional");
        }
        if (method.nameIsUserDefined) {
            notes.push("method name is chosen by the service author; declare one such method per use");
        }
        const repeatable = (method.parameters ?? []).filter((p) => p.repeatable);
        if (repeatable.length > 0) {
            notes.push(`repeatable: ${repeatable.map((p) => p.type.name).join(", ")}`);
        }
        const noteComment = notes.length > 0 ? ` // ${notes.join("; ")}` : "";

        lines.push(`${desc}${dep}${anns}    remote function ${method.name}(${params})${returnStr};${noteComment}`);
        lines.push("");
    }

    // Remove trailing empty line
    if (lines[lines.length - 1] === "") {
        lines.pop();
    }

    lines.push("}");
    return lines.join("\n");
}

/**
 * Groups catalog entries by annotation name, preserving first-seen order. The catalog carries one
 * entry per attachment point, whereas Ballerina source declares an annotation once with a
 * comma-separated `on` list.
 */
function groupAnnotationsByName(annotations: Annotation[]): Annotation[][] {
    const groups = new Map<string, Annotation[]>();
    for (const annotation of annotations) {
        const group = groups.get(annotation.name);
        if (group) {
            group.push(annotation);
        } else {
            groups.set(annotation.name, [annotation]);
        }
    }
    return [...groups.values()];
}

/**
 * Renders all catalog entries of one annotation as a single
 * `public annotation ... on <point>[, <point>...];` declaration.
 * Returns `null` when none of the entries' attachment points have a Ballerina token mapping.
 */
function renderAnnotation(entries: Annotation[]): string | null {
    const resolvedPoints = entries
        .map((entry) => resolveAttachmentPoint(entry.attachmentPoint))
        .filter((point): point is AttachmentPointLabel => point !== null);
    const points = [...new Set(resolvedPoints.map((point) => point.label))];
    if (points.length === 0) {
        return null;
    }
    // A declaration that uses any source-only point must be a `const` declaration.
    const constModifier = resolvedPoints.some((point) => point.sourceOnly) ? "const " : "";

    const annotation = entries[0];
    const description = entries.find((entry) => entry.description)?.description;
    const typeConstraint = entries.find((entry) => entry.typeConstraint)?.typeConstraint;

    const lines: string[] = [];
    if (description) {
        const descBody = description
            .split("\n")
            .map((l) => `# ${l}`)
            .join("\n");
        lines.push(descBody);
    }

    let typeSlot = "";
    let agentNote = "";
    if (typeConstraint) {
        const externalLinks = collectExternalLinks(typeConstraint);
        const typeName = applyPrefixToTypeName(typeConstraint.name, externalLinks);
        typeSlot = `${typeName} `;
        agentNote = buildSpecialAgentNote(externalLinks);
    }
    lines.push(`public ${constModifier}annotation ${typeSlot}${annotation.name} on ${points.join(", ")};`
        + `${agentNote}`);
    return lines.join("\n");
}

/**
 * Renders a service to Ballerina syntax.
 */
function renderService(service: Service): string {
    if (service.type === "generic") {
        return renderGenericService(service as GenericService);
    } else {
        return renderFixedService(service as FixedService);
    }
}

/**
 * Converts an array of Library objects to LLM-friendly Ballerina syntax string.
 */
export function toSyntaxString(libraries: Library[]): string {
    const output: string[] = [];

    for (const lib of libraries) {
        // Library header
        output.push(`// ============================================================`);
        output.push(`// Library: ${lib.name}`);
        if (lib.description) {
            output.push(`// ${lib.description.split("\n")[0]}`);
        }
        output.push(`// ============================================================`);
        output.push(`import ${lib.name};`);

        // Instructions (prepended if present)
        if (lib.instructions) {
            output.push("");
            output.push(lib.instructions);
        }

        // README (prepended if present)
        if (lib.readme) {
            output.push("");
            output.push("// --- README ---");
            output.push(lib.readme);
            output.push("// --- END README ---");
        }

        // Types section
        if (lib.typeDefs && lib.typeDefs.length > 0) {
            output.push("");
            output.push("// --- Types ---");
            for (const typeDef of lib.typeDefs) {
                output.push("");
                output.push(renderTypeDef(typeDef));
            }
        }

        // Client section
        if (lib.clients && lib.clients.length > 0) {
            output.push("");
            output.push("// --- Client ---");
            for (const client of lib.clients) {
                output.push("");
                output.push(renderClient(client));
            }
        }

        // Functions section
        if (lib.functions && lib.functions.length > 0) {
            output.push("");
            output.push("// --- Functions ---");
            for (const func of lib.functions) {
                output.push("");
                output.push(renderStandaloneFunction(func));
            }
        }

        // Service section
        if (lib.services && lib.services.length > 0) {
            output.push("");
            output.push("// --- Service ---");
            for (const service of lib.services) {
                output.push("");
                output.push(renderService(service));
            }
        }

        // Annotation section
        if (lib.annotations && lib.annotations.length > 0) {
            const renderedAnnotations = groupAnnotationsByName(lib.annotations)
                .map(renderAnnotation)
                .filter((rendered): rendered is string => rendered !== null);
            if (renderedAnnotations.length > 0) {
                output.push("");
                output.push("// --- Annotations ---");
                for (const rendered of renderedAnnotations) {
                    output.push("");
                    output.push(rendered);
                }
            }
        }

        output.push("");
    }

    return output.join("\n");
}
