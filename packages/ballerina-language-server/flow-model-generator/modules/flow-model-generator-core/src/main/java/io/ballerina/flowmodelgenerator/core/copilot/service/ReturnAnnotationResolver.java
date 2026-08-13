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

import java.util.List;

/**
 * Owns <b>spec §8 at {@code attachPoint: "return"}</b>: the annotations a handler's return may carry.
 *
 * <p><b>Resolved by id, per handler.</b> Spec v1.0 closed §8's "Residual gap" by giving return scope its
 * own forward reference, {@code handlers.options[].returnAnnotations}, so this reads the registry the same
 * way the handler and parameter scopes do.
 *
 * <p>The change is not merely tidier. Selection used to be by attach point, which is a <i>document</i>-wide
 * question, so every return-pointed annotation attached to every handler of every service type that
 * {@code appliesTo} admitted. {@code ballerina/http}'s {@code $cache} would therefore have landed on
 * handlers whose return is not cacheable at all. A per-handler list is what makes "this handler's return may
 * carry a cache directive, that one's may not" expressible.
 *
 * <p>It targets a different syntactic slot from {@link HandlerAnnotationResolver} — {@code returns
 * @http:Cache {...} T} rather than a declaration-level attachment — which is why the two cannot share a
 * component even though they now share an access path.
 *
 * @since 1.7.0
 */
final class ReturnAnnotationResolver {

    private ReturnAnnotationResolver() {
        // Prevent instantiation
    }

    /**
     * Resolves the annotations one handler must or may carry on its return.
     *
     * @param registry   the document's §8 registry
     * @param ids        the handler's {@code returnAnnotations} ids; may be {@code null}
     * @param homeModule spec §1's home module
     * @param facts      the compiler-backed facts; {@code null} skips the checks that need them
     * @return the references to emit and the entries dropped
     */
    static AnnotationScopeResolver.Resolution resolve(AnnotationRegistry registry, List<String> ids,
                                                      String homeModule,
                                                      AnnotationScopeResolver.AnnotationFacts facts) {
        // Spec v1.0 gave return scope its own forward reference, `handlers.options[].returnAnnotations`.
        // It used to be selected by attach point, which meant every return-pointed annotation in the
        // document attached to every handler — http's `$cache` would have landed on handlers that never
        // return a cacheable value. A per-handler list is what makes that expressible.
        return AnnotationScopeResolver.byIds(registry, ids, AnnotationScopeResolver.Scope.RETURN,
                homeModule, facts);
    }
}
