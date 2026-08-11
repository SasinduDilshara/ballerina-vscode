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
 * <b>Spec §3 {@code multipleListenersAllowed} / {@code multipleServicesPerListenerAllowed}</b>: both are
 * stated, not left to a default.
 *
 * <p>The schema already lists both in {@code serviceType.required}, so this check is not re-deciding
 * whether they are mandatory — it is the part of that requirement the pipeline can enforce at load time,
 * against a document the schema never ran over (a connector's own shipped
 * {@code resources/trigger-metadata.json}, which no build validates).
 *
 * <p><b>Why an omission is worth reporting at all.</b> The consumer states only the <i>prohibition</i> —
 * it emits "this service type attaches to exactly one listener" for {@code false} and says nothing for
 * {@code true}, because the one-service-one-listener shape a generator writes by default is legal either
 * way. Both fields are boxed so an absent key arrives as {@code null} and is read as permissive rather
 * than as {@code false}; without that, an omission would silently manufacture a restriction the connector
 * never imposed. The boxing makes the omission harmless; this check makes it visible, so a new document
 * does not quietly rely on a default the spec does not grant it.
 *
 * <p>Reported as a <b>warning</b>: the document is still fully usable, and every service type in the
 * bundled corpus states both keys today.
 *
 * @since 1.10.0
 */
final class CardinalityCheck implements DocumentCheck {

    @Override
    public String id() {
        return "cardinality";
    }

    @Override
    public String specSection() {
        return "§3";
    }

    @Override
    public List<Finding> check(TriggerMetadataModel document) {
        List<Finding> findings = new ArrayList<>();
        List<TriggerMetadataModel.ServiceType> serviceTypes = DocumentWalk.safe(document.serviceTypes());
        for (int i = 0; i < serviceTypes.size(); i++) {
            TriggerMetadataModel.ServiceType serviceType = serviceTypes.get(i);
            if (serviceType == null) {
                // Reported by ServiceTypeRefCheck; nothing to add here.
                continue;
            }
            String path = DocumentWalk.serviceTypePath(i);
            if (serviceType.multipleListenersAllowed() == null) {
                findings.add(Finding.warn(this, path + ".multipleListenersAllowed",
                        "absent: read as permissive. State it explicitly — the consumer emits a note only "
                                + "for `false`, so an omission and a `true` are indistinguishable"));
            }
            if (serviceType.multipleServicesPerListenerAllowed() == null) {
                findings.add(Finding.warn(this, path + ".multipleServicesPerListenerAllowed",
                        "absent: read as permissive. State it explicitly — the consumer emits a note only "
                                + "for `false`, so an omission and a `true` are indistinguishable"));
            }
        }
        return findings;
    }
}
