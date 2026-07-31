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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.ballerina.compiler.api.SemanticModel;
import io.ballerina.compiler.api.symbols.AnnotationSymbol;
import io.ballerina.compiler.api.symbols.RecordFieldSymbol;
import io.ballerina.compiler.api.symbols.RecordTypeSymbol;
import io.ballerina.compiler.api.symbols.Symbol;
import io.ballerina.compiler.api.symbols.SymbolKind;
import io.ballerina.compiler.api.symbols.TypeSymbol;
import io.ballerina.modelgenerator.commons.CommonUtils;
import io.ballerina.modelgenerator.commons.DefaultValueGeneratorUtil;
import io.ballerina.modelgenerator.commons.PackageUtil;
import io.ballerina.modelgenerator.commons.trigger.models.TriggerMetadataModel;
import io.ballerina.modelgenerator.commons.trigger.models.TypeRef;
import io.ballerina.projects.Package;
import org.ballerinalang.langserver.common.utils.RecordUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.logging.Logger;

/**
 * Resolves a trigger metadata document's annotation <b>bindings</b> — which annotation belongs on
 * which service type, handler, or handler parameter — into the Copilot service contract.
 *
 * <p>This is the one class of annotation information no Semantic Model can produce. A marker service
 * type such as {@code mcp:Service} is declared {@code distinct service object { }} — literally empty
 * — so there is no symbol carrying a handler, a handler parameter, or an attachment. The authoring
 * document is the only source that states the binding, whether it is mandatory
 * ({@code presence: "required"}), and which of several same-attach-point annotations belongs to which
 * service type ({@code appliesTo}). It is also the only source for a <em>cross-module</em> binding
 * (e.g. {@code ballerina/http}'s {@code Header} on an {@code mcp} handler parameter), which the
 * {@code mcp} package's own module symbols cannot contain.</p>
 *
 * <p>Everything is derived from two inputs and nothing else — no library is named or special-cased
 * anywhere in this class:</p>
 * <ol>
 *   <li>the metadata document, for the binding and its {@code presence}; and</li>
 *   <li>the Semantic Model of whichever module <em>declares</em> the annotation, for the annotation's
 *       constraint type. The attachment body is generated from that type by
 *       {@link DefaultValueGeneratorUtil}, which emits the record's mandatory fields recursively —
 *       the only form that compiles. (An empty body is rejected for a constraint with a required
 *       field, and a bare attachment is rejected outright for such a constraint, so the body cannot
 *       be guessed or omitted.)</li>
 * </ol>
 *
 * <p>A cross-module annotation's declaring package is resolved on demand and cached; when it cannot
 * be resolved the binding is still emitted, just without a generated body.</p>
 *
 * @since 1.7.0
 */
final class TriggerAnnotationBinder {

    private static final Logger LOGGER = Logger.getLogger(TriggerAnnotationBinder.class.getName());

    /** The document's {@code attachPoint} value for a service-level annotation. */
    private static final String ATTACH_POINT_SERVICE = "service";
    private static final String PRESENCE_REQUIRED = "required";
    /** A rule whose members are mutually-exclusive alternatives rather than joint requirements. */
    private static final String RULE_TYPE_ONE_OF = "oneOf";

    private final Map<String, TriggerMetadataModel.Annotation> annotationsById = new LinkedHashMap<>();
    /** Ids reached by any per-site reference: a handler, a parameter, or a rule member. */
    private final Set<String> referencedIds = new HashSet<>();
    /** Service-type id to the annotation ids its {@code rules} reference. */
    private final Map<String, Set<String>> ruleIdsByServiceType = new HashMap<>();
    /**
     * Annotation ids that appear only as alternatives of a {@code oneOf} rule. Such an annotation is
     * one of several ways to supply a value, so it must not be reported as mandatory however the
     * registry marks its {@code presence}.
     */
    private final Set<String> oneOfAlternativeIds = new HashSet<>();
    /** Annotation id to the record fields the document's rules name on it. */
    private final Map<String, Set<String>> ruleFieldsByAnnotation = new HashMap<>();

    private final SemanticModel ownSemanticModel;
    private final String org;
    private final String packageName;

    /** Resolved semantic models of foreign declaring modules, keyed {@code org/package}. */
    private final Map<String, SemanticModel> foreignModels = new HashMap<>();
    private final Set<String> unresolvableForeignPackages = new HashSet<>();
    /** Generated attachment bodies, keyed by the annotation's document id. */
    private final Map<String, String> bodyCache = new HashMap<>();

    TriggerAnnotationBinder(TriggerMetadataModel metadata, SemanticModel ownSemanticModel, String org,
                            String packageName) {
        this.ownSemanticModel = ownSemanticModel;
        this.org = org;
        this.packageName = packageName;
        index(metadata);
    }

    private void index(TriggerMetadataModel metadata) {
        if (metadata.annotations() != null) {
            for (TriggerMetadataModel.Annotation annotation : metadata.annotations()) {
                if (annotation != null && annotation.id() != null && annotation.type() != null
                        && annotation.type().name() != null) {
                    annotationsById.put(annotation.id(), annotation);
                }
            }
        }
        if (metadata.serviceTypes() == null) {
            return;
        }
        for (TriggerMetadataModel.ServiceType serviceType : metadata.serviceTypes()) {
            if (serviceType == null) {
                continue;
            }
            TriggerMetadataModel.ServiceType.Handlers handlers = serviceType.handlers();
            if (handlers != null && handlers.options() != null) {
                for (TriggerMetadataModel.ServiceType.HandlerOption option : handlers.options()) {
                    if (option == null) {
                        continue;
                    }
                    addAll(referencedIds, option.annotations());
                    if (option.params() != null) {
                        for (TriggerMetadataModel.ServiceType.Param param : option.params()) {
                            if (param != null) {
                                addAll(referencedIds, param.annotations());
                            }
                        }
                    }
                }
            }
            if (serviceType.rules() != null && serviceType.id() != null) {
                Set<String> fromRules = ruleIdsByServiceType.computeIfAbsent(serviceType.id(), k -> new HashSet<>());
                for (TriggerMetadataModel.ServiceType.Rule rule : serviceType.rules()) {
                    if (rule == null || rule.members() == null) {
                        continue;
                    }
                    boolean oneOf = RULE_TYPE_ONE_OF.equals(rule.type());
                    for (TriggerMetadataModel.ServiceType.Rule.RuleMember member : rule.members()) {
                        if (member == null || member.annotation() == null) {
                            continue;
                        }
                        fromRules.add(member.annotation());
                        referencedIds.add(member.annotation());
                        if (oneOf) {
                            oneOfAlternativeIds.add(member.annotation());
                        }
                        if (member.field() != null) {
                            ruleFieldsByAnnotation
                                    .computeIfAbsent(member.annotation(), k -> new LinkedHashSet<>())
                                    .add(member.field());
                        }
                    }
                }
            }
        }
    }

    private static void addAll(Set<String> target, List<String> ids) {
        if (ids != null) {
            for (String id : ids) {
                if (id != null) {
                    target.add(id);
                }
            }
        }
    }

    /**
     * The service-point bindings of one service type. An annotation is linked to a service type when
     * its {@code appliesTo} names the type, or one of that type's {@code rules} references it. An
     * annotation the document links to nothing more specific applies to every service type — that is
     * the only reading left, since {@code appliesTo} is documented as being omitted precisely when
     * some other reference already pins the annotation down.
     */
    JsonArray forServiceType(String serviceTypeId) {
        List<String> ids = new ArrayList<>();
        for (TriggerMetadataModel.Annotation annotation : annotationsById.values()) {
            if (!ATTACH_POINT_SERVICE.equals(annotation.attachPoint())) {
                continue;
            }
            if (linkedToServiceType(annotation, serviceTypeId)) {
                ids.add(annotation.id());
            }
        }
        return forIds(ids);
    }

    private boolean linkedToServiceType(TriggerMetadataModel.Annotation annotation, String serviceTypeId) {
        List<String> appliesTo = annotation.appliesTo();
        if (appliesTo != null && !appliesTo.isEmpty()) {
            return serviceTypeId != null && appliesTo.contains(serviceTypeId);
        }
        Set<String> fromRules = ruleIdsByServiceType.get(serviceTypeId);
        if (fromRules != null && fromRules.contains(annotation.id())) {
            return true;
        }
        return !referencedIds.contains(annotation.id());
    }

    /**
     * Builds the attachment array for the given document annotation ids, in document order. Unknown
     * ids are skipped rather than guessed at.
     *
     * @param ids annotation ids, as they appear in a handler's or parameter's {@code annotations}
     * @return the attachments, or an empty array
     */
    JsonArray forIds(List<String> ids) {
        JsonArray attachments = new JsonArray();
        if (ids == null || ids.isEmpty()) {
            return attachments;
        }
        Set<String> emitted = new HashSet<>();
        for (String id : ids) {
            TriggerMetadataModel.Annotation annotation = id == null ? null : annotationsById.get(id);
            if (annotation == null || !emitted.add(id)) {
                continue;
            }
            attachments.add(toAttachment(annotation));
        }
        return attachments;
    }

    private JsonObject toAttachment(TriggerMetadataModel.Annotation annotation) {
        JsonObject attachment = new JsonObject();
        attachment.addProperty("name", annotation.type().name());
        attachment.addProperty("module", declaringModuleIdentifier(annotation.type().packageInfo()));
        String body = bodyFor(annotation);
        if (body != null && !body.isEmpty()) {
            attachment.addProperty("value", body);
        }
        // A `oneOf` alternative is never mandatory: the document offers it as one of several ways to
        // supply the value, so `presence: required` describes the value, not the attachment.
        if (PRESENCE_REQUIRED.equals(annotation.presence())
                && !oneOfAlternativeIds.contains(annotation.id())) {
            attachment.addProperty("required", true);
        }
        return attachment;
    }

    /**
     * The {@code org/module} identifier of the module that declares the annotation — always emitted,
     * including for the library being processed.
     *
     * <p>A binding is rendered inside a <em>service template</em>, i.e. code the user writes against
     * an imported module, so the annotation is module-qualified there just as the service type
     * ({@code ftp:Service}) and the listener ({@code ftp:Listener}) beside it are. That differs from a
     * per-symbol attachment, which is rendered as part of the library's own declarations and so stays
     * bare for the library's own annotations.</p>
     *
     * <p>The shape is the same {@code org/module} the per-symbol extractor emits, so the renderer
     * derives the alias identically for both.</p>
     */
    private String declaringModuleIdentifier(TypeRef.PackageInfo packageInfo) {
        if (packageInfo == null || packageInfo.packageName() == null) {
            return org + "/" + packageName;
        }
        String moduleName = packageInfo.moduleName() != null ? packageInfo.moduleName() : packageInfo.packageName();
        String owningOrg = packageInfo.org() != null ? packageInfo.org() : org;
        return owningOrg + "/" + moduleName;
    }

    /**
     * Generates the attachment body from the annotation's constraint type, read out of whichever
     * module declares the annotation. Returns {@code null} when the annotation has no constraint
     * (a bare attachment is then correct) or when the declaring module cannot be resolved.
     */
    private String bodyFor(TriggerMetadataModel.Annotation annotation) {
        if (bodyCache.containsKey(annotation.id())) {
            return bodyCache.get(annotation.id());
        }
        String body = null;
        try {
            SemanticModel model = declaringModel(annotation.type().packageInfo());
            if (model != null) {
                body = findAnnotation(model, annotation.type().name())
                        .flatMap(AnnotationSymbol::typeDescriptor)
                        .map(constraint -> renderBody(constraint,
                                ruleFieldsByAnnotation.getOrDefault(annotation.id(), Set.of())))
                        .filter(rendered -> rendered.startsWith("{"))
                        .orElse(null);
            }
        } catch (RuntimeException e) {
            LOGGER.warning("Failed to generate an attachment body for @" + annotation.type().name()
                    + ": " + e.getMessage());
        }
        bodyCache.put(annotation.id(), body);
        return body;
    }

    /**
     * Renders the attachment body for a constraint type.
     *
     * <p>The base is what must be written for the attachment to compile: the record's mandatory fields,
     * recursively, via {@link DefaultValueGeneratorUtil}. On top of that, any field the document's own
     * {@code rules} name on this annotation is included even when the record makes it optional —
     * a rule naming {@code serviceConfig.path} is the document stating that this is the field the
     * service author is expected to supply, which an empty body would hide.</p>
     *
     * <p>Anything that is not a record constraint falls through to
     * {@link DefaultValueGeneratorUtil}, and a non-mapping result is rejected by the caller: an
     * annotation value may only be a mapping constructor, so such an annotation is attached bare.</p>
     */
    private static String renderBody(TypeSymbol constraint, Set<String> ruleFields) {
        TypeSymbol rawType = CommonUtils.getRawType(constraint);
        if (ruleFields.isEmpty() || !(rawType instanceof RecordTypeSymbol recordType)) {
            return DefaultValueGeneratorUtil.getDefaultValueForType(constraint);
        }

        Map<String, RecordFieldSymbol> fields = new LinkedHashMap<>();
        for (RecordFieldSymbol mandatory : RecordUtil.getMandatoryRecordFields(recordType)) {
            mandatory.getName().ifPresent(name -> fields.put(name, mandatory));
        }
        for (Map.Entry<String, ? extends RecordFieldSymbol> entry : recordType.fieldDescriptors().entrySet()) {
            String name = entry.getValue().getName().orElse(entry.getKey());
            if (ruleFields.contains(name)) {
                fields.put(name, entry.getValue());
            }
        }
        if (fields.isEmpty()) {
            return DefaultValueGeneratorUtil.getDefaultValueForType(constraint);
        }

        StringJoiner joiner = new StringJoiner(", ", "{", "}");
        for (Map.Entry<String, RecordFieldSymbol> entry : fields.entrySet()) {
            joiner.add(entry.getKey() + ": "
                    + DefaultValueGeneratorUtil.getDefaultValueForType(entry.getValue().typeDescriptor()));
        }
        return joiner.toString();
    }

    private static Optional<AnnotationSymbol> findAnnotation(SemanticModel model, String name) {
        for (Symbol symbol : model.moduleSymbols()) {
            if (symbol.kind() == SymbolKind.ANNOTATION && name.equals(symbol.getName().orElse(null))) {
                return Optional.of((AnnotationSymbol) symbol);
            }
        }
        return Optional.empty();
    }

    /**
     * The Semantic Model of the module that declares the annotation: this library's own model for a
     * same-package annotation, otherwise the foreign package resolved on demand and cached. A
     * package that cannot be resolved is remembered so it is attempted only once.
     */
    private SemanticModel declaringModel(TypeRef.PackageInfo packageInfo) {
        if (packageInfo == null || packageInfo.packageName() == null
                || packageInfo.packageName().equals(packageName)) {
            return ownSemanticModel;
        }
        String foreignOrg = packageInfo.org() != null ? packageInfo.org() : org;
        String key = foreignOrg + "/" + packageInfo.packageName();
        if (foreignModels.containsKey(key)) {
            return foreignModels.get(key);
        }
        if (unresolvableForeignPackages.contains(key)) {
            return null;
        }
        // Resolution reaches Ballerina Central and throws — not merely returns empty — for an unknown
        // package or when offline, so every failure mode has to land in the negative cache or the same
        // lookup would be retried for every annotation from that package.
        try {
            Optional<Package> optPackage = PackageUtil.getModulePackage(
                    PackageUtil.getSampleProject(), foreignOrg, packageInfo.packageName());
            if (optPackage.isEmpty()) {
                LOGGER.warning("Could not resolve " + key + " to generate its annotation bodies");
                unresolvableForeignPackages.add(key);
                return null;
            }
            Package foreignPackage = optPackage.get();
            SemanticModel model = PackageUtil.getCompilation(foreignPackage)
                    .getSemanticModel(foreignPackage.getDefaultModule().moduleId());
            foreignModels.put(key, model);
            return model;
        } catch (Throwable t) {
            LOGGER.warning("Failed to resolve " + key + " to generate its annotation bodies: " + t);
            unresolvableForeignPackages.add(key);
            return null;
        }
    }
}
