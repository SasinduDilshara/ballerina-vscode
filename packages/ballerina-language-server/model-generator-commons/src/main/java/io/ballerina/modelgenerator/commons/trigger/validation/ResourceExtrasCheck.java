/*
 *  Copyright (c) 2026, WSO2 LLC. (http://www.wso2.com)
 *
 *  WSO2 LLC. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package io.ballerina.modelgenerator.commons.trigger.validation;

import io.ballerina.modelgenerator.commons.trigger.models.TriggerMetadataModel;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>Spec §5 resource extras</b>: a {@code resource} handler is identified by its {@code accessor} and its
 * {@code path}; a {@code remote} handler has neither.
 *
 * <h2>What spec §5 fixed here</h2>
 *
 * <p>There used to be five keys across two protocol families — HTTP's {@code method}/{@code path} and
 * GraphQL's {@code accessor}/{@code fieldName}/{@code graphqlOperation} — and this check carried two
 * severities to cope with the fact that §5's grouping did not survive contact with the corpus: GraphQL's
 * mutation is legitimately {@code kind: "remote"} and still carried {@code fieldName} and
 * {@code graphqlOperation}, so erroring on them would have deleted true information.
 *
 * <p>§5 collapsed all five into the two positions the language actually has, and made the rule symmetric:
 * "Both are required for {@code kind: "resource"} and neither applies to {@code kind: "remote"}." The
 * GraphQL keys are gone because they were derivable — a query is {@code resource} with accessor
 * {@code get}, a subscription is {@code resource} with accessor {@code subscribe}, and a mutation is
 * {@code remote}. With nothing left that is meaningful-but-misplaced, the WARN tier disappears and both
 * halves are ERRORs.
 *
 * @since 1.10.0
 */
final class ResourceExtrasCheck implements DocumentCheck {

    private static final String RESOURCE = TriggerMetadataModel.ServiceType.HandlerOption.KIND_RESOURCE;

    @Override
    public String id() {
        return "resourceExtras";
    }

    @Override
    public String specSection() {
        return "§5";
    }

    @Override
    public List<Finding> check(TriggerMetadataModel document) {
        List<Finding> findings = new ArrayList<>();
        List<TriggerMetadataModel.ServiceType> serviceTypes = DocumentWalk.safe(document.serviceTypes());
        for (int i = 0; i < serviceTypes.size(); i++) {
            List<TriggerMetadataModel.ServiceType.HandlerOption> options =
                    DocumentWalk.options(serviceTypes.get(i));
            for (int j = 0; j < options.size(); j++) {
                TriggerMetadataModel.ServiceType.HandlerOption option = options.get(j);
                if (option == null) {
                    continue;
                }
                String path = DocumentWalk.optionPath(i, j);
                if (RESOURCE.equals(option.kind())) {
                    // Without these a resource signature cannot be written at all: the language form is
                    // `resource function <accessor> <path>()`.
                    missing(findings, option.accessor() == null, path, "accessor");
                    missing(findings, option.path() == null, path, "path");
                } else {
                    present(findings, option.accessor() != null, path, "accessor");
                    present(findings, option.path() != null, path, "path");
                }
            }
        }
        return findings;
    }

    private void missing(List<Finding> findings, boolean absent, String path, String key) {
        if (absent) {
            findings.add(Finding.error(this, path + "." + key,
                    "required for kind 'resource'; the language form is `resource function <accessor> "
                            + "<path>()`, so a resource handler cannot be written without it"));
        }
    }

    private void present(List<Finding> findings, boolean present, String path, String key) {
        if (present) {
            findings.add(Finding.error(this, path + "." + key,
                    "stated on a handler whose kind is not 'resource'; a remote function has no "
                            + key + " position"));
        }
    }
}
