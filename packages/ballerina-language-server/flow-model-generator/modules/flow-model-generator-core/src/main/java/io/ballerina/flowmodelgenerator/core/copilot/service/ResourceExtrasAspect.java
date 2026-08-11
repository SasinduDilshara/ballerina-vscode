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

package io.ballerina.flowmodelgenerator.core.copilot.service;

/**
 * Spec §5's resource extras — the accessor and path of {@code resource function <accessor> <path>()}.
 *
 * <p>One aspect for both protocol families, because spec §5 made the two slots library-neutral. It replaces
 * the separate HTTP and GraphQL aspects, which existed only because the schema used to name the same two
 * positions differently per protocol.
 *
 * @since 1.10.0
 */
final class ResourceExtrasAspect implements HandlerAspect {

    @Override
    public String id() {
        return "resourceExtras";
    }

    @Override
    public String specSection() {
        return "§5";
    }

    @Override
    public void contribute(HandlerScope scope, HandlerDraft draft) {
        if (scope.isConcrete()) {
            // A concrete service type's methods are read from the semantic model, which already carries
            // the accessor in the declaration itself.
            return;
        }
        ResourceExtrasResolver.resolve(scope.option()).ifPresent(extras -> {
            draft.setAccessor(extras.accessor());
            draft.setAccessorConstraint(extras.accessorValues(), extras.accessorRequired(),
                    extras.accessorOpen());
            draft.setPathConstraint(extras.path(), extras.pathValues(), extras.pathRequired(),
                    extras.pathOpen());
        });
    }
}
