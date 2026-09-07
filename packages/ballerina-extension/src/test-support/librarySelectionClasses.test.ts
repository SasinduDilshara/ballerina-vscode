/**
 * Copyright (c) 2026, WSO2 LLC. (https://www.wso2.com) All Rights Reserved.
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

/**
 * @jest-environment node
 *
 * Classes reach the generating model only through selection. `ai:Agent`, `ai:VectorKnowledgeBase` and
 * `email:ImapListener` are constructed with `new` and driven through their own methods, so no function
 * signature names them and the type-reference closure never pulled them in — the model never saw
 * `Agent.run(string|Prompt|Resume ...)`, `KnowledgeBase.ingest`/`retrieve`, or the human-in-the-loop
 * records those signatures name. These tests pin the request side (classes are sent), the response side
 * (kept classes are re-inflated with their constructor and only the kept methods), and the merge with the
 * reference-pulled type definitions.
 */

import {
    collectClassFunctions,
    getClassFunctionCount,
    hasNothingToSelect,
    isSelectableClass,
    mergeClassTypeDefs,
    selectClasses,
    toRequestClasses,
    toSelectionRequest,
} from '../features/ai/utils/libs/library-selection';
import { getFunctionsResponseSchema } from '../features/ai/utils/libs/function-types';
import {
    ClassTypeDefinition,
    Library,
    RecordTypeDefinition,
    RemoteFunction,
    ResourceFunction,
    TypeDefinition,
} from '../features/ai/utils/libs/library-types';
import { toSyntaxString } from '../features/ai/utils/libs/to-syntax-string';

function method(name: string, type: string, params: string[], returnType: string, links?: string[]): RemoteFunction {
    return {
        type,
        name,
        description: `${name} doc`,
        parameters: params.map((p) => ({ name: p, description: '', type: { name: 'string' } })),
        return: { type: { name: returnType, ...(links ? { links: links.map((l) => ({ category: 'internal' as const, recordName: l })) } : {}) } },
    };
}

function resource(accessor: string, paths: string[]): ResourceFunction {
    return {
        type: 'Resource Function',
        accessor,
        paths,
        description: '',
        parameters: [],
        return: { type: { name: 'error?' } },
    };
}

const runMethod: RemoteFunction = {
    type: 'Normal Function',
    name: 'run',
    description: 'Runs the agent',
    parameters: [
        {
            name: 'query',
            description: '',
            type: {
                name: 'string|Prompt|Resume',
                links: [
                    { category: 'internal', recordName: 'Prompt' },
                    { category: 'internal', recordName: 'Resume' },
                ],
            },
        },
        { name: 'sessionId', description: '', type: { name: 'string' }, default: '"default"' },
    ],
    return: { type: { name: 'string|Error' } },
};

const agentClass: ClassTypeDefinition = {
    name: 'Agent',
    description: 'An AI agent',
    type: 'Class',
    functions: [
        { ...method('init', 'Constructor', ['systemPrompt', 'model'], 'Error?'), name: 'init' },
        runMethod,
        method('getTools', 'Normal Function', [], 'ToolConfig[]'),
    ],
};

const listenerClass: ClassTypeDefinition = {
    name: 'ImapListener',
    description: 'Polls an IMAP mailbox',
    type: 'Class',
    functions: [
        method('init', 'Constructor', ['listenerConfig'], 'Error?'),
        method('attach', 'Normal Function', ['s', 'name'], 'error?'),
        method('start', 'Normal Function', [], 'error?'),
    ],
};

const markerClass: ClassTypeDefinition = {
    name: 'Service',
    description: 'Marker service type',
    type: 'Class',
    functions: [],
};

const constructorOnlyClass: ClassTypeDefinition = {
    name: 'Chunker',
    description: 'Only constructible',
    type: 'Class',
    functions: [method('init', 'Constructor', ['maxChunkSize'], '')],
};

const resumeRecord: RecordTypeDefinition = {
    name: 'Resume',
    description: 'Resumes a paused run',
    type: 'Record',
    fields: [{ name: 'decisions', description: '', type: { name: 'map<HumanResponse>' } }],
};

const promptRecord: RecordTypeDefinition = {
    name: 'Prompt',
    description: 'A prompt',
    type: 'Record',
    fields: [],
};

const typeDefs: TypeDefinition[] = [agentClass, listenerClass, markerClass, constructorOnlyClass, resumeRecord, promptRecord];

function library(): Library {
    return {
        name: 'ballerina/ai',
        description: 'AI',
        typeDefs,
        clients: [],
        functions: [],
        services: [],
    };
}

describe('isSelectableClass', () => {
    it('is true only for a class that declares a method other than its constructor', () => {
        expect(isSelectableClass(agentClass)).toBe(true);
        expect(isSelectableClass(listenerClass)).toBe(true);
    });

    it('is false for marker classes, constructor-only classes and non-class definitions', () => {
        expect(isSelectableClass(markerClass)).toBe(false);
        expect(isSelectableClass(constructorOnlyClass)).toBe(false);
        expect(isSelectableClass(resumeRecord)).toBe(false);
    });
});

describe('toRequestClasses', () => {
    it('minifies every selectable class like a client and omits the constructor', () => {
        const classes = toRequestClasses(typeDefs)!;
        expect(classes.map((c) => c.name)).toEqual(['Agent', 'ImapListener']);
        const agent = classes[0];
        expect(agent.description).toBe('An AI agent');
        expect(agent.functions).toEqual([
            { name: 'run', parameters: ['query', 'sessionId'], returnType: 'string|Error' },
            { name: 'getTools', parameters: [], returnType: 'ToolConfig[]' },
        ]);
    });

    it('is undefined when the library has no selectable class, so the request shape is unchanged', () => {
        expect(toRequestClasses([markerClass, resumeRecord])).toBeUndefined();
        expect(toRequestClasses(undefined)).toBeUndefined();
        expect(toSelectionRequest({ ...library(), typeDefs: [resumeRecord] }, false)).not.toHaveProperty('classes');
    });

    it('toSelectionRequest carries the classes alongside clients, functions and services', () => {
        const request = toSelectionRequest(library(), false);
        expect(request.classes?.map((c) => c.name)).toEqual(['Agent', 'ImapListener']);
        expect(getClassFunctionCount(request.classes)).toBe(4);
    });

    it('a class-only library is a selection decision, not a passthrough', () => {
        const request = toSelectionRequest(library(), false);
        expect(hasNothingToSelect(request)).toBe(false);
        expect(hasNothingToSelect({ ...request, classes: undefined })).toBe(true);
    });
});

describe('selectClasses', () => {
    it('re-inflates a kept class with its constructor and only the kept methods, in order', () => {
        const [agent] = selectClasses(typeDefs, {
            name: 'ballerina/ai',
            classes: [{ name: 'Agent', functions: [{ name: 'run' }] }],
        });
        expect(agent.name).toBe('Agent');
        expect(agent.type).toBe('Class');
        expect(agent.functions.map((f: RemoteFunction) => f.name)).toEqual(['init', 'run']);
        // The complete declaration, not the minified one: parameter types and links survive.
        expect(agent.functions[1]).toBe(runMethod);
    });

    it('resolves resource methods by accessor and path', () => {
        const withResource: ClassTypeDefinition = {
            name: 'Req',
            description: '',
            type: 'Class',
            functions: [resource('get', ['items', 'all']), resource('post', ['items'])],
        };
        const [req] = selectClasses([withResource], {
            name: 'lib',
            classes: [{ name: 'Req', functions: [{ accessor: 'post', paths: ['items'] }] }],
        });
        expect(req.functions).toEqual([withResource.functions[1]]);
    });

    it('drops classes and methods the library does not declare rather than inventing them', () => {
        const kept = selectClasses(typeDefs, {
            name: 'ballerina/ai',
            classes: [
                { name: 'Nope', functions: [{ name: 'run' }] },
                { name: 'Agent', functions: [{ name: 'fly' }] },
            ],
        });
        expect(kept).toEqual([]);
    });

    it('returns nothing when the response names no classes', () => {
        expect(selectClasses(typeDefs, { name: 'ballerina/ai' })).toEqual([]);
        expect(selectClasses(undefined, { name: 'ballerina/ai', classes: [{ name: 'Agent', functions: [{ name: 'run' }] }] })).toEqual([]);
    });
});

describe('mergeClassTypeDefs', () => {
    it('appends selected classes that the reference closure did not already pull in', () => {
        const [agent] = selectClasses(typeDefs, { name: 'ballerina/ai', classes: [{ name: 'Agent', functions: [{ name: 'run' }] }] });
        const merged = mergeClassTypeDefs([resumeRecord], [agent]);
        expect(merged.map((t) => t.name)).toEqual(['Resume', 'Agent']);
    });

    it('keeps the referenced copy when a class is both referenced and selected', () => {
        const [partial] = selectClasses(typeDefs, { name: 'ballerina/ai', classes: [{ name: 'Agent', functions: [{ name: 'run' }] }] });
        const merged = mergeClassTypeDefs([agentClass], [partial]);
        expect(merged).toEqual([agentClass]);
    });
});

describe('collectClassFunctions', () => {
    it('yields every method of every class definition so the type scanners can walk their signatures', () => {
        const functions = collectClassFunctions(typeDefs);
        expect(functions).toContain(runMethod);
        expect(functions.length).toBe(agentClass.functions.length + listenerClass.functions.length + constructorOnlyClass.functions.length);
        expect(collectClassFunctions(undefined)).toEqual([]);
    });
});

describe('response schema', () => {
    it('accepts a classes field shaped like clients', () => {
        const parsed = getFunctionsResponseSchema.safeParse({
            libraries: [{ name: 'ballerina/ai', classes: [{ name: 'Agent', functions: [{ name: 'run' }] }] }],
        });
        expect(parsed.success).toBe(true);
    });
});

describe('end to end: a selected class renders with its members', () => {
    it('renders the kept Agent methods and the Resume record the run signature names', () => {
        const [agent] = selectClasses(typeDefs, { name: 'ballerina/ai', classes: [{ name: 'Agent', functions: [{ name: 'run' }] }] });
        const rendered = toSyntaxString([{ ...library(), typeDefs: mergeClassTypeDefs([resumeRecord], [agent]) }]);
        expect(rendered).toContain('class Agent {');
        expect(rendered).toContain('function init(string systemPrompt, string model) returns Error?;');
        expect(rendered).toContain('function run(string|Prompt|Resume query, string sessionId = "default") returns string|Error;');
        expect(rendered).not.toContain('getTools');
        expect(rendered).toContain('type Resume record {');
    });
});
