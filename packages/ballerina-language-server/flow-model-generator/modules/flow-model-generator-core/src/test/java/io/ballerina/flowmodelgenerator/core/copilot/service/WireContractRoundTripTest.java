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

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;
import io.ballerina.flowmodelgenerator.core.copilot.model.Listener;
import io.ballerina.flowmodelgenerator.core.copilot.model.Parameter;
import io.ballerina.flowmodelgenerator.core.copilot.model.Service;
import io.ballerina.flowmodelgenerator.core.copilot.model.ServiceRemoteFunction;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Guards <b>the one boundary in this pipeline that fails silently</b>: the JSON round trip between the
 * drafts that write a service and the POJOs that read it back.
 *
 * <h2>Why this exists</h2>
 *
 * <p>{@code CopilotLibraryManager} deserializes the pipeline's own output through {@link Service} before
 * anything downstream sees it. Gson drops an unknown key without complaint, so a field the drafts emit and
 * the POJO does not declare is deleted — with no compile error, no exception, no log line, and no failing
 * test anywhere in the suite. The only symptom is content quietly missing from the rendered prompt.
 *
 * <p>This has now happened twice. When spec §9 was reshaped, {@code binding} arrived as {@code {}} because
 * the POJO still declared the old key. When spec §5 collapsed the resource extras,
 * {@link ServiceRemoteFunction} kept {@code methodValues}/{@code pathForm}/{@code graphqlOperation} and
 * never gained {@code accessorValues}/{@code accessorRequired}/{@code accessorOpen} — so the pipeline
 * resolved all eight of {@code ballerina/http}'s accessors, the renderer had a branch ready to print them,
 * and the entire vocabulary vanished on this hop. Both were found by eyeballing a rendered diff, which is
 * not a control.
 *
 * <p>So the assertion is deliberately mechanical: take the key set each draft can emit, and require the
 * POJO to declare every one. It is coupled to the drafts on purpose — a new spec field should fail here
 * until it is carried the whole way, which is precisely the failure the two incidents lacked.
 *
 * @since 1.10.0
 */
public class WireContractRoundTripTest {

    private static final Gson GSON = new Gson();

    @Test
    public void testEveryHandlerKeyTheDraftEmitsSurvivesTheRoundTrip() {
        HandlerDraft draft = new HandlerDraft();
        draft.setName("onEvent");
        draft.setKind("resource");
        draft.setIsolated();
        draft.setDescription("What this handler is for.");
        draft.setOptional(false);
        draft.setAccessor("get");
        draft.setAccessorConstraint(List.of("get", "post"), true, false);
        // Both halves of §5's shared `valueSpec`, so the sweep covers the path vocabulary too. It did not,
        // which is how `path.values` came to be resolved nowhere and declared nowhere.
        draft.setPathConstraint("orders", List.of("orders", "invoices"), true, false);
        draft.setDeprecated("Superseded by the typed handlers.");
        draft.setReturn(new JsonObject());

        assertEveryKeyIsDeclared(draft.toJson(), ServiceRemoteFunction.class, "handler");
    }

    @Test
    public void testAnOpenAccessorSurvivesTheRoundTrip() {
        // The `accessorOpen` half of §5 has no corpus instance yet, so it cannot be caught by rendering the
        // corpus -- which is exactly the shape of gap that let the enumerated half go missing.
        HandlerDraft draft = new HandlerDraft();
        draft.setName("onEvent");
        draft.setKind("resource");
        draft.setAccessorConstraint(List.of(), true, true);

        ServiceRemoteFunction handler = GSON.fromJson(draft.toJson(), ServiceRemoteFunction.class);
        Assert.assertEquals(handler.isAccessorOpen(), Boolean.TRUE);
        Assert.assertEquals(handler.isAccessorRequired(), Boolean.TRUE);
    }

    @Test
    public void testTheAccessorVocabularySurvivesWithItsValues() {
        // The regression itself, pinned by value rather than by key presence: `ballerina/http` declares
        // eight accessors, and losing them cost the prompt its only statement of which verbs are legal.
        HandlerDraft draft = new HandlerDraft();
        draft.setName("get");
        draft.setKind("resource");
        draft.setAccessor("get");
        draft.setAccessorConstraint(
                List.of("get", "post", "put", "delete", "patch", "head", "options", "default"), true, false);
        draft.setPathConstraint(null, null, true, false);

        ServiceRemoteFunction handler = GSON.fromJson(draft.toJson(), ServiceRemoteFunction.class);
        Assert.assertEquals(handler.getAccessorValues().size(), 8, "every legal accessor must survive");
        Assert.assertEquals(handler.getAccessorValues().get(0), "get");
        Assert.assertEquals(handler.isAccessorRequired(), Boolean.TRUE);
        Assert.assertEquals(handler.isPathRequired(), Boolean.TRUE);
        Assert.assertNull(handler.isAccessorOpen(), "an enumerated slot is not an open one");
    }

    @Test
    public void testThePathVocabularySurvivesWithItsValues() {
        // The accessor half of this exact regression was found by eyeballing a render; the path half could
        // not be, because no corpus document sets `path.values`. Pinned by value rather than key presence.
        HandlerDraft draft = new HandlerDraft();
        draft.setName("get");
        draft.setKind("resource");
        draft.setPathConstraint("orders", List.of("orders", "invoices"), true, false);

        ServiceRemoteFunction handler = GSON.fromJson(draft.toJson(), ServiceRemoteFunction.class);
        Assert.assertEquals(handler.getPath(), "orders", "spec §1: the first declared value is the default");
        Assert.assertEquals(handler.getPathValues(), List.of("orders", "invoices"));
        Assert.assertEquals(handler.isPathRequired(), Boolean.TRUE);
        Assert.assertNull(handler.isPathOpen(), "an enumerated slot is not an open one");
    }

    @Test
    public void testAnOpenPathSurvivesTheRoundTrip() {
        HandlerDraft draft = new HandlerDraft();
        draft.setName("get");
        draft.setKind("resource");
        draft.setPathConstraint(null, List.of(), true, true);

        ServiceRemoteFunction handler = GSON.fromJson(draft.toJson(), ServiceRemoteFunction.class);
        Assert.assertEquals(handler.isPathOpen(), Boolean.TRUE);
        Assert.assertNull(handler.getPath(), "an open slot has no single value to write");
    }

    @Test
    public void testEveryParamKeyTheDraftEmitsSurvivesTheRoundTrip() {
        ParamDraft draft = new ParamDraft();
        draft.setName("event");
        draft.setDescription("What this parameter carries.");
        draft.setDeprecated("Use the Context parameter instead.");
        draft.setType(new JsonObject());
        draft.setOptional(true);
        draft.setRepeatable(true);
        draft.setBinding(new JsonObject());

        assertEveryKeyIsDeclared(draft.toJson(), Parameter.class, "parameter");
    }

    @Test
    public void testTheHandlerDeprecationProseSurvivesAsProseRatherThanAsAFlag() {
        // `deprecated` (prose, from the document) and `isDeprecated` (a flag, from the compiled symbol) are
        // different facts on the same object. Binding both to one field would either lose the sentence --
        // the only part that names a replacement -- or fail to parse a string into a Boolean.
        HandlerDraft draft = new HandlerDraft();
        draft.setName("onFileChange");
        draft.setDeprecated("Superseded by onFileText, onFileJson, onFileXml, onFileCsv and onFile.");

        ServiceRemoteFunction handler = GSON.fromJson(draft.toJson(), ServiceRemoteFunction.class);
        Assert.assertTrue(handler.getDeprecationNote().startsWith("Superseded by onFileText"));
        Assert.assertNull(handler.isDeprecated(), "the document states no symbol-level annotation");
    }

    @Test
    public void testTheListenerDeprecationProseSurvivesTheRoundTrip() {
        // §2's listener prose is written by ListenerAspect straight into the listener object, so it never
        // passes through a draft and would not be covered by the key sweep above.
        JsonObject listener = new JsonObject();
        listener.addProperty("name", "websocket:Listener");
        listener.addProperty("deprecated", "Use websocket:HttpListener.");

        Assert.assertEquals(GSON.fromJson(listener, Listener.class).getDeprecationNote(),
                "Use websocket:HttpListener.");
    }

    @Test
    public void testTheServiceTypeDeprecationProseSurvivesTheRoundTrip() {
        ServiceDraft draft = new ServiceDraft();
        draft.setKind("fixed");
        draft.setName("Service");
        draft.setDeprecated("Use websocket:UpgradeService.");

        Assert.assertEquals(GSON.fromJson(draft.toJson(), Service.class).getDeprecationNote(),
                "Use websocket:UpgradeService.");
    }

    // ---- the sweep -------------------------------------------------------------------

    /**
     * Requires every key in {@code emitted} to be declared by {@code pojo}, under its own name or a
     * {@link SerializedName} alias.
     *
     * <p>Reports the whole missing set at once rather than the first offender: a spec change typically
     * adds or renames several keys together, and fixing them one failing run at a time is how the second
     * incident took as long as it did.
     */
    private static void assertEveryKeyIsDeclared(JsonObject emitted, Class<?> pojo, String scope) {
        Set<String> declared = new LinkedHashSet<>();
        for (Field field : pojo.getDeclaredFields()) {
            SerializedName alias = field.getAnnotation(SerializedName.class);
            declared.add(alias != null ? alias.value() : field.getName());
        }
        List<String> missing = new ArrayList<>();
        for (String key : emitted.keySet()) {
            if (!declared.contains(key)) {
                missing.add(key);
            }
        }
        Assert.assertTrue(missing.isEmpty(),
                "The " + scope + " draft emits " + missing + ", which " + pojo.getSimpleName()
                        + " does not declare. Gson drops an unknown key silently, so these are deleted "
                        + "before anything renders them. Declared: " + declared);
    }
}
