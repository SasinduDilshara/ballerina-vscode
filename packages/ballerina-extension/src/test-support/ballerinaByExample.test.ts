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
 * A Ballerina By Example page is rendered client-side; fetching it returns the site's navigation and none
 * of the example. `web_fetch` therefore resolves such a URL to the example's source in the distribution
 * repository. These tests pin the URL shapes that are (and are not) rewritten and the slug-to-file rule.
 */

import {
    BALLERINA_BY_EXAMPLE_SOURCE_HOST,
    resolveBallerinaByExampleSource,
    withByExampleSourceHost,
} from '../features/ai/agent/tools/ballerina-by-example';

const EXAMPLES = 'https://raw.githubusercontent.com/ballerina-platform/ballerina-distribution/master/examples';

describe('resolveBallerinaByExampleSource', () => {
    it('maps a by-example page to its .bal source, hyphens becoming underscores in the file name', () => {
        const resolved = resolveBallerinaByExampleSource('https://ballerina.io/learn/by-example/receive-email-using-service/');
        expect(resolved).toEqual({
            slug: 'receive-email-using-service',
            sourceUrl: `${EXAMPLES}/receive-email-using-service/receive_email_using_service.bal`,
            descriptionUrl: `${EXAMPLES}/receive-email-using-service/receive_email_using_service.md`,
        });
    });

    it.each([
        'https://ballerina.io/learn/by-example/rag-ingestion-with-external-vector-store',
        'http://ballerina.io/learn/by-example/rag-ingestion-with-external-vector-store/',
        'https://www.ballerina.io/learn/by-example/rag-ingestion-with-external-vector-store/?x=1',
        'https://ballerina.io/learn/by-example/rag-ingestion-with-external-vector-store/#section',
        '  https://ballerina.io/learn/by-example/RAG-Ingestion-With-External-Vector-Store/  ',
    ])('tolerates scheme, www, trailing slash, query, fragment, case and whitespace: %s', (url) => {
        expect(resolveBallerinaByExampleSource(url)?.sourceUrl)
            .toBe(`${EXAMPLES}/rag-ingestion-with-external-vector-store/rag_ingestion_with_external_vector_store.bal`);
    });

    it.each([
        'https://ballerina.io/learn/by-example/',
        'https://ballerina.io/learn/by-example',
        'https://ballerina.io/learn/get-started/',
        'https://ballerina.io/learn/by-example/a/b/',
        'https://central.ballerina.io/ballerina/email/latest',
        'https://docs.github.com/en/rest/releases/assets',
        'https://evil.example/https://ballerina.io/learn/by-example/foo/',
        'not a url',
    ])('leaves every other URL alone: %s', (url) => {
        expect(resolveBallerinaByExampleSource(url)).toBeNull();
    });
});

describe('withByExampleSourceHost', () => {
    it('adds the raw GitHub host to a model-supplied allow-list so the rewritten fetch is not blocked', () => {
        expect(withByExampleSourceHost(['ballerina.io'])).toEqual(['ballerina.io', BALLERINA_BY_EXAMPLE_SOURCE_HOST]);
    });

    it('does not duplicate the host and leaves an absent allow-list absent', () => {
        expect(withByExampleSourceHost(['ballerina.io', BALLERINA_BY_EXAMPLE_SOURCE_HOST]))
            .toEqual(['ballerina.io', BALLERINA_BY_EXAMPLE_SOURCE_HOST]);
        expect(withByExampleSourceHost(undefined)).toBeUndefined();
        expect(withByExampleSourceHost([])).toEqual([]);
    });
});
