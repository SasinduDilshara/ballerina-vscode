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
 * <b>Spec §9 shape conditionals</b>: which fields each {@code shapes[]} entry must and may populate.
 *
 * <p>Spec §9's shape table assigns fields per form — {@code bare} takes none, {@code array} takes
 * {@code element}, {@code stream} takes {@code element} and {@code completionType}, {@code included} takes
 * {@code envelope} and {@code bindableFields}.
 *
 * <p><b>One conditional the published {@code spec.json} does not enforce.</b> Its {@code shape} definition
 * requires {@code envelope}/{@code bindableFields} only when {@code form} is {@code "included"}, but the
 * corpus's real batched-envelope shape is {@code {form: "array", element: "included", envelope: …,
 * bindableFields: …}} — kafka's. Nothing in the schema requires an envelope there, so a document could say
 * {@code element: "included"} and omit the record being included, leaving a consumer describing an
 * inclusion of nothing. That gap is closed here, since a JSON-schema validator would not catch it.
 *
 * @since 1.10.0
 */
final class BindingModeCheck implements DocumentCheck {

    @Override
    public String id() {
        return "bindingMode";
    }

    @Override
    public String specSection() {
        return "§9";
    }

    @Override
    public List<Finding> check(TriggerMetadataModel document) {
        List<Finding> findings = new ArrayList<>();
        BindingWalk.forEachBinding(document, (binding, path) -> {
            List<TriggerMetadataModel.TypedescVariant> variants = DocumentWalk.safe(binding.typedescs());
            for (int i = 0; i < variants.size(); i++) {
                TriggerMetadataModel.TypedescVariant variant = variants.get(i);
                if (variant == null) {
                    continue;
                }
                String variantPath = path + ".typedescs[" + i + "]";
                List<TriggerMetadataModel.Shape> shapes = DocumentWalk.safe(variant.shapes());
                if (shapes.isEmpty()) {
                    findings.add(Finding.error(this, variantPath + ".shapes",
                            "a variant with no shapes states no way to embed its bound type"));
                }
                for (int j = 0; j < shapes.size(); j++) {
                    if (shapes.get(j) != null) {
                        checkShape(findings, shapes.get(j), variantPath + ".shapes[" + j + "]");
                    }
                }
            }
        });
        return findings;
    }

    private void checkShape(List<Finding> findings, TriggerMetadataModel.Shape shape, String path) {
        String form = shape.form();
        boolean array = TriggerMetadataModel.Shape.FORM_ARRAY.equals(form);
        boolean stream = TriggerMetadataModel.Shape.FORM_STREAM.equals(form);
        boolean included = TriggerMetadataModel.Shape.FORM_INCLUDED.equals(form);
        boolean bare = TriggerMetadataModel.Shape.FORM_BARE.equals(form);

        if (array || stream) {
            if (shape.element() == null || shape.element().isBlank()) {
                findings.add(Finding.error(this, path,
                        "`" + form + "` states no `element`, so whether each item is bare or included is "
                                + "unstated"));
            }
        } else if (shape.element() != null) {
            findings.add(Finding.error(this, path,
                    "`element` belongs to `array`/`stream`, not `" + form + "`"));
        }

        if (stream && DocumentWalk.safe(shape.completionType()).isEmpty()) {
            // Not fatal to a reader, but a stream whose completion type is unstated cannot be written:
            // `stream<T>` and `stream<T, error?>` are different types.
            findings.add(Finding.warn(this, path,
                    "`stream` states no `completionType`; a reader cannot tell `stream<T>` from "
                            + "`stream<T, error?>`"));
        }
        if (!stream && shape.completionType() != null) {
            findings.add(Finding.error(this, path, "`completionType` belongs to `stream`, not `" + form + "`"));
        }

        // The envelope is required by `included`, and equally by an array/stream OF included elements —
        // the conditional spec.json omits.
        boolean includesElements = included
                || ((array || stream) && TriggerMetadataModel.Shape.FORM_INCLUDED.equals(shape.element()));
        if (includesElements) {
            if (shape.envelope() == null) {
                findings.add(Finding.error(this, path,
                        "an included shape states no `envelope`, so there is no record to include"));
            }
            if (shape.bindableFields() == null || shape.bindableFields().isEmpty()) {
                findings.add(Finding.error(this, path,
                        "an included shape states no `bindableFields`, so every field of the envelope "
                                + "stays fixed and the binding projects nothing"));
            }
        } else {
            if (shape.envelope() != null) {
                findings.add(Finding.error(this, path,
                        "`envelope` belongs to an included shape, not `" + form
                                + (bare ? "" : "`/`element: " + shape.element()) + "`"));
            }
            if (shape.bindableFields() != null) {
                findings.add(Finding.error(this, path,
                        "`bindableFields` belongs to an included shape, not `" + form + "`"));
            }
        }
    }
}
