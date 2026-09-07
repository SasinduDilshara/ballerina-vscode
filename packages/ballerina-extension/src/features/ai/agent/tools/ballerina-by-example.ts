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
 * Resolves a Ballerina By Example page to the source it renders.
 *
 * `https://ballerina.io/learn/by-example/<slug>/` is rendered client-side: fetching the page returns the
 * site's navigation menu and none of the example, so a user who pastes one of these URLs (which is the
 * documented way to point Copilot at a canonical sample) gets back nothing usable and the model concludes
 * the API is undocumented. Every example is a directory in the `ballerina-distribution` repository whose
 * `.bal` file is the page's content, named after the slug with hyphens turned to underscores — so the raw
 * GitHub file is fetched instead.
 *
 * Kept free of `vscode`/`ai` imports so it can be unit tested in isolation.
 */

export const BALLERINA_BY_EXAMPLE_SOURCE_HOST = "raw.githubusercontent.com";

const BALLERINA_BY_EXAMPLE_URL =
    /^https?:\/\/(?:www\.)?ballerina\.io\/learn\/by-example\/([a-z0-9][a-z0-9-]*)\/?(?:[?#].*)?$/i;

const BALLERINA_DISTRIBUTION_EXAMPLES =
    "https://raw.githubusercontent.com/ballerina-platform/ballerina-distribution/master/examples";

export interface BallerinaByExampleSource {
    /** The example directory name, as it appears in the URL. */
    slug: string;
    /** The raw `.bal` file that the page renders. */
    sourceUrl: string;
    /** The raw `.md` file with the example's description; fetched only if the source is unavailable. */
    descriptionUrl: string;
}

/**
 * The raw source location for a Ballerina By Example page, or `null` for any other URL — including the
 * by-example index itself, which has no slug and no single source file.
 */
export function resolveBallerinaByExampleSource(url: string): BallerinaByExampleSource | null {
    const match = BALLERINA_BY_EXAMPLE_URL.exec(url.trim());
    if (!match) {
        return null;
    }
    const slug = match[1].toLowerCase();
    const fileStem = slug.replace(/-/g, "_");
    return {
        slug,
        sourceUrl: `${BALLERINA_DISTRIBUTION_EXAMPLES}/${slug}/${fileStem}.bal`,
        descriptionUrl: `${BALLERINA_DISTRIBUTION_EXAMPLES}/${slug}/${fileStem}.md`,
    };
}

/**
 * The domain allow-list to use for a rewritten fetch. The model typically pins `allowed_domains` to
 * `ballerina.io` when the user pasted a ballerina.io URL; without adding the raw GitHub host the rewritten
 * fetch would be silently blocked by the very filter that was meant to keep it on topic.
 */
export function withByExampleSourceHost(allowedDomains: string[] | undefined): string[] | undefined {
    if (!allowedDomains || allowedDomains.length === 0) {
        return allowedDomains;
    }
    return allowedDomains.includes(BALLERINA_BY_EXAMPLE_SOURCE_HOST)
        ? allowedDomains
        : [...allowedDomains, BALLERINA_BY_EXAMPLE_SOURCE_HOST];
}
