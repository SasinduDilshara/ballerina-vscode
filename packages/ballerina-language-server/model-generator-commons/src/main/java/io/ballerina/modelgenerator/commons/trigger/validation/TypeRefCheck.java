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
import io.ballerina.modelgenerator.commons.trigger.models.TypeRef;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * <b>Spec §1 {@code TypeRef}</b>: every type reference names a type, and a cross-module one carries
 * complete coordinates.
 *
 * <p>Incomplete {@code packageInfo} is the interesting case. Spec §1 shows all four keys populated, and
 * the resolver derives both the import path and the alias from {@code moduleName}/{@code org}; a reference
 * missing either is silently treated as <b>same-module</b>, which renders a foreign type with the
 * connector's own prefix — a name that does not resolve, reported by nothing.
 *
 * @since 1.10.0
 */
final class TypeRefCheck implements DocumentCheck {

    /** Spec §1's own pattern for a named node. */
    private static final Pattern NAME = Pattern.compile("^(\\(\\)|record \\{\\}|[A-Za-z_][A-Za-z0-9_]*)$");

    @Override
    public String id() {
        return "typeRef";
    }

    @Override
    public String specSection() {
        return "§1";
    }

    @Override
    public List<Finding> check(TriggerMetadataModel document) {
        List<Finding> findings = new ArrayList<>();

        List<TriggerMetadataModel.Listener> listeners = DocumentWalk.safe(document.listeners());
        for (int i = 0; i < listeners.size(); i++) {
            if (listeners.get(i) != null) {
                typeRef(findings, listeners.get(i).type(), "listeners[" + i + "].type");
            }
        }

        List<TriggerMetadataModel.ServiceType> serviceTypes = DocumentWalk.safe(document.serviceTypes());
        for (int i = 0; i < serviceTypes.size(); i++) {
            TriggerMetadataModel.ServiceType serviceType = serviceTypes.get(i);
            if (serviceType == null) {
                continue;
            }
            typeRef(findings, serviceType.type(), DocumentWalk.serviceTypePath(i) + ".type");
            List<TriggerMetadataModel.ServiceType.HandlerOption> options = DocumentWalk.options(serviceType);
            for (int j = 0; j < options.size(); j++) {
                TriggerMetadataModel.ServiceType.HandlerOption option = options.get(j);
                if (option == null) {
                    continue;
                }
                String optionPath = DocumentWalk.optionPath(i, j);
                for (TypeRef ref : DocumentWalk.safe(option.returns())) {
                    typeRef(findings, ref, optionPath + ".returns");
                }
                List<TriggerMetadataModel.ServiceType.Param> params = DocumentWalk.safe(option.params());
                for (int k = 0; k < params.size(); k++) {
                    if (params.get(k) == null) {
                        continue;
                    }
                    String paramPath = DocumentWalk.paramPath(i, j, k);
                    List<TypeRef> types = DocumentWalk.safe(params.get(k).type());
                    if (types.isEmpty()) {
                        findings.add(Finding.error(this, paramPath + ".type",
                                "required: a parameter slot must state its legal type(s)"));
                    }
                    for (TypeRef ref : types) {
                        typeRef(findings, ref, paramPath + ".type");
                    }
                    dataBinding(findings, params.get(k).dataBinding(), paramPath + ".dataBinding");
                }
            }
        }

        for (TriggerMetadataModel.Annotation annotation : DocumentWalk.safe(document.annotations())) {
            if (annotation != null) {
                typeRef(findings, annotation.type(), "annotations[" + annotation.id() + "].type");
            }
        }
        return findings;
    }

    /**
     * Spec §9's type references, which live inline on the parameter rather than in a registry. Walked from
     * the parameter loop above so every finding is reported against the slot that owns the binding — under
     * the old top-level registry a bad reference could only be reported against a rule id, leaving the
     * reader to find which parameters used it.
     */
    private void dataBinding(List<Finding> findings, TriggerMetadataModel.DataBinding binding, String path) {
        if (binding == null) {
            return;
        }
        List<TriggerMetadataModel.TypedescVariant> variants = DocumentWalk.safe(binding.typedescs());
        for (int i = 0; i < variants.size(); i++) {
            TriggerMetadataModel.TypedescVariant variant = variants.get(i);
            if (variant == null) {
                continue;
            }
            String variantPath = path + ".typedescs[" + i + "]";
            typeRef(findings, variant.constraint(), variantPath + ".constraint");
            for (TypeRef ref : DocumentWalk.safe(variant.excludes())) {
                typeRef(findings, ref, variantPath + ".excludes");
            }
            List<TriggerMetadataModel.Shape> shapes = DocumentWalk.safe(variant.shapes());
            for (int j = 0; j < shapes.size(); j++) {
                TriggerMetadataModel.Shape shape = shapes.get(j);
                if (shape == null) {
                    continue;
                }
                String shapePath = variantPath + ".shapes[" + j + "]";
                if (shape.envelope() != null) {
                    typeRef(findings, shape.envelope(), shapePath + ".envelope");
                }
                for (TypeRef member : DocumentWalk.safe(shape.completionType())) {
                    typeRef(findings, member, shapePath + ".completionType");
                }
            }
        }
    }

    /**
     * Checks one node of a {@link TypeRef} tree, recursing into its parts.
     *
     * <p>Spec §1 makes a node either a plain {@code name} or a constructed type given by {@code shape}.
     * The two are mutually exclusive, and an unknown {@code shape} is an <b>ERROR</b> rather than a warning:
     * §1.1 is explicit that the shape vocabulary is closed, unlike the §6.2 rule registry, "because the type
     * could not be written at all, so it should fail loudly rather than silently".
     */
    private void typeRef(List<Finding> findings, TypeRef ref, String path) {
        if (ref == null) {
            findings.add(Finding.error(this, path, "missing type reference"));
            return;
        }
        if (ref.isComposite()) {
            compositeTypeRef(findings, ref, path);
            return;
        }
        if (ref.name() == null || ref.name().isBlank()) {
            findings.add(Finding.error(this, path,
                    "a TypeRef must carry either a `name` or a `shape`"));
            return;
        }
        // Spec §1: a name never embeds [], <> or a trailing ?, because those are shapes and unions now.
        // Only () and record {} are non-identifier names.
        if (!NAME.matcher(ref.name()).matches()) {
            findings.add(Finding.error(this, path,
                    "'" + ref.name() + "' is not a bare type name; spec §1 expresses arrays, streams and"
                            + " nilability as shapes and unions, so a name carries no [], <> or trailing ?"));
        }
        packageInfo(findings, ref, path);
    }

    private void compositeTypeRef(List<Finding> findings, TypeRef ref, String path) {
        if (ref.name() != null) {
            findings.add(Finding.error(this, path,
                    "carries both `name` and `shape`; spec §1 makes them mutually exclusive"));
        }
        boolean array = TypeRef.SHAPE_ARRAY.equals(ref.shape());
        boolean stream = TypeRef.SHAPE_STREAM.equals(ref.shape());
        if (!array && !stream) {
            findings.add(Finding.error(this, path + ".shape",
                    "'" + ref.shape() + "' is not a shape this build implements; spec §1.1 closes the shape"
                            + " vocabulary, so an unrecognised one cannot be skipped — the type could not"
                            + " be written at all"));
            return;
        }
        List<TypeRef> element = DocumentWalk.safe(ref.elementType());
        if (element.isEmpty()) {
            findings.add(Finding.error(this, path + ".elementType",
                    "required: a '" + ref.shape() + "' with no element states no type"));
        }
        for (TypeRef member : element) {
            typeRef(findings, member, path + ".elementType");
        }
        if (array && ref.completionType() != null) {
            findings.add(Finding.error(this, path + ".completionType",
                    "an array terminates with nothing, so it has no completionType"));
        }
        for (TypeRef member : DocumentWalk.safe(ref.completionType())) {
            typeRef(findings, member, path + ".completionType");
        }
    }

    private void packageInfo(List<Finding> findings, TypeRef ref, String path) {
        TypeRef.PackageInfo info = ref.packageInfo();
        if (info == null) {
            return;
        }
        if (info.org() == null || info.org().isBlank()) {
            findings.add(Finding.error(this, path + ".packageInfo.org",
                    "required: without it the reference is read as same-module and rendered with the "
                            + "connector's own prefix"));
        }
        if ((info.moduleName() == null || info.moduleName().isBlank())
                && (info.packageName() == null || info.packageName().isBlank())) {
            findings.add(Finding.error(this, path + ".packageInfo",
                    "needs `moduleName` (or at least `packageName`) to derive the import alias"));
        }
    }
}
