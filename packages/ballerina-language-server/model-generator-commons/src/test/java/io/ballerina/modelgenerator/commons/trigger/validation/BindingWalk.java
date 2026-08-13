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

import java.util.List;
import java.util.function.BiConsumer;

/**
 * Visits every {@code params[].dataBinding} in a document, with the path of the parameter that owns it.
 *
 * <p>Its own helper rather than a method on {@link DocumentWalk} because spec §9 moved bindings <b>inline
 * onto the parameter</b>: reaching one now means descending service type → option → param, which is four
 * nested loops that two separate checks would otherwise each write out. When the binding was a top-level
 * registry this was a single {@code for} and needed no helper.
 *
 * <p>The path handed to the visitor is the parameter's, extended with {@code .dataBinding}, so a finding
 * points at the slot a document author has to edit rather than at a shared rule they would then have to
 * trace back to its users.
 *
 * @since 1.10.0
 */
final class BindingWalk {

    private BindingWalk() {
        // Prevent instantiation
    }

    /**
     * Applies {@code visitor} to every declared binding.
     *
     * @param document the document
     * @param visitor  receives the binding and the JSON path of the parameter declaring it
     */
    static void forEachBinding(TriggerMetadataModel document,
                               BiConsumer<TriggerMetadataModel.DataBinding, String> visitor) {
        List<TriggerMetadataModel.ServiceType> serviceTypes = DocumentWalk.safe(document.serviceTypes());
        for (int i = 0; i < serviceTypes.size(); i++) {
            List<TriggerMetadataModel.ServiceType.HandlerOption> options =
                    DocumentWalk.options(serviceTypes.get(i));
            for (int j = 0; j < options.size(); j++) {
                if (options.get(j) == null) {
                    continue;
                }
                List<TriggerMetadataModel.ServiceType.Param> params =
                        DocumentWalk.safe(options.get(j).params());
                for (int k = 0; k < params.size(); k++) {
                    TriggerMetadataModel.ServiceType.Param param = params.get(k);
                    if (param == null || param.dataBinding() == null) {
                        continue;
                    }
                    visitor.accept(param.dataBinding(),
                            DocumentWalk.paramPath(i, j, k) + ".dataBinding");
                }
            }
        }
    }
}
