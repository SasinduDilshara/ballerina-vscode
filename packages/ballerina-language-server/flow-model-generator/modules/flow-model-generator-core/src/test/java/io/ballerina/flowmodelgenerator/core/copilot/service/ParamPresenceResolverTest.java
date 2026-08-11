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

import com.google.gson.JsonObject;
import io.ballerina.modelgenerator.commons.trigger.models.TriggerMetadataModel;
import io.ballerina.modelgenerator.commons.trigger.models.TypeRef;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.List;

/**
 * Conformance tests for <b>spec §7 {@code params[].presence}</b>, written against the spec text.
 *
 * <p>Spec statement pinned: {@code presence} is {@code "required"} or {@code "optional"}, and an optional
 * slot is one the service author may leave out of the signature altogether.
 *
 * <p>These moved here from {@code ParamTypeResolverTest} when {@code presence} gained its own owner, for
 * the same reason {@code addMode}'s did: a slot carries three independent modifiers, and while two of them
 * shared a resolver a change to either had two plausible homes.
 *
 * @since 1.10.0
 */
public class ParamPresenceResolverTest {

    @Test
    public void testOnlyAnOptionalSlotIsOptional() {
        Assert.assertTrue(ParamPresenceResolver.isOptional(param("optional")));
        Assert.assertFalse(ParamPresenceResolver.isOptional(param("required")));
    }

    @Test
    public void testAbsentPresenceIsNotOptional() {
        // `required` is the safe reading of an unstated presence: emitting a required slot that is in
        // fact optional still compiles, whereas omitting a required one does not.
        Assert.assertFalse(ParamPresenceResolver.isOptional(param(null)));
    }

    @Test
    public void testAnUnrecognisedPresenceIsNotOptional() {
        // Same asymmetry, applied to a token outside spec §10's vocabulary: a future value must not be
        // guessed into permission the document did not grant.
        Assert.assertFalse(ParamPresenceResolver.isOptional(param("someFuturePresence")));
    }

    @Test
    public void testANullSlotIsNotOptional() {
        Assert.assertFalse(ParamPresenceResolver.isOptional((TriggerMetadataModel.ServiceType.Param) null));
    }

    /**
     * A concrete service type's parameter answers from the compiler, not the document.
     *
     * <p>Both sources resolve here so that "is this slot optional?" has one answer regardless of where the
     * handler came from — the reason this component takes the declared parameter as well.
     */
    @Test
    public void testADeclaredParameterAnswersFromTheSemanticModel() {
        Assert.assertTrue(ParamPresenceResolver.isOptional(
                new TriggerSemanticFacts.DeclaredParam("caller", "kafka:Caller", "", true)));
        Assert.assertFalse(ParamPresenceResolver.isOptional(
                new TriggerSemanticFacts.DeclaredParam("records", "kafka:Record[]", "", false)));
        Assert.assertFalse(ParamPresenceResolver.isOptional((TriggerSemanticFacts.DeclaredParam) null));
    }

    /** The aspect writes the flag under the omission rule: present only when the slot is optional. */
    @Test
    public void testTheAspectEmitsTheKeyOnlyForAnOptionalSlot() {
        Assert.assertTrue(contribute("optional").has("optional"));
        Assert.assertFalse(contribute("required").has("optional"),
                "a required slot states nothing — absent means required on the wire");
    }

    private static JsonObject contribute(String presence) {
        ParamDraft draft = new ParamDraft();
        TriggerScope service = new TriggerScope("ballerinax/probe", "ballerinax", "probe", "probe", null,
                AnnotationRegistry.of(null), null, null, null, null, name -> false);
        HandlerScope handler = new HandlerScope(service, null, null);
        new ParamPresenceAspect().contribute(
                new ParamScope(handler, param(presence), null, 0, java.util.Set.of()), draft);
        return draft.toJson();
    }

    private static TriggerMetadataModel.ServiceType.Param param(String presence) {
        return new TriggerMetadataModel.ServiceType.Param("caller", null, null, List.of(new TypeRef("Caller", null)),
                presence, null, null, null);
    }
}
