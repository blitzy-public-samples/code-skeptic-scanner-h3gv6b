package com.codeskeptic.scanner.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

import com.codeskeptic.scanner.dto.SettingDto;
import com.codeskeptic.scanner.exception.BadRequestException;
import com.codeskeptic.scanner.exception.NotFoundException;
import com.codeskeptic.scanner.service.SettingsService;

// Ported from backend/tests/test_api.py:L36-44 and backend/app/api/settings.py:L7-24 — see
// docs/DECISION_LOG.md DL-039, DL-048, DL-050, DL-188
/**
 * Exercises the two routes {@link SettingController} serves — {@code GET /settings} and
 * {@code PUT /settings/{key}} — through {@link MockMvc} against a mocked {@link SettingsService}.
 *
 * <p>The slice starts the controller, {@link GlobalExceptionHandler} and the framework's own message
 * conversion and validation, so every status and every wire literal asserted here is produced by the
 * same code that produces it at runtime. The service is mocked, so no database is opened and no
 * configuration key is resolved; what is asserted is the HTTP contract, not the persistence behaviour
 * that {@code service.SettingsServiceTest} covers.
 *
 * <p>The route contract this class pins comes from {@code backend/app/api/settings.py}:
 *
 * <table border="1">
 * <caption>Contract asserted</caption>
 * <tr><th>Condition</th><th>Status</th><th>Body</th><th>Source</th></tr>
 * <tr><td>read every row</td><td>200</td><td>a JSON array of three-member objects</td>
 *     <td>{@code :L7-11}</td></tr>
 * <tr><td>replace a value</td><td>200</td><td>one three-member object</td><td>{@code :L13-24}</td></tr>
 * <tr><td>{@code value} absent or {@code null}</td><td>400</td>
 *     <td>{@code {"error":"No value provided"}}</td><td>{@code :L17-18}</td></tr>
 * <tr><td>{@code key} names no row</td><td>404</td>
 *     <td>{@code {"error":"Setting not found"}}</td><td>{@code :L21-22}</td></tr>
 * <tr><td>body the converter cannot bind</td><td>400</td>
 *     <td>{@code {"error":"Bad request"}}</td><td>net-new — DL-092, DL-188</td></tr>
 * </table>
 *
 * <p>The slice runs without the security filter chain and asserts the route and advice responses.
 */
@WebMvcTest(SettingController.class)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@DisplayName("SettingController")
class SettingControllerTest {

    /** Key of the row every positive case addresses. */
    private static final String KEY = "tweet_popularity_threshold";

    /** Key that names no row. */
    private static final String ABSENT_KEY = "does_not_exist";

    /** Description carried by the row {@link #KEY} names. */
    private static final String DESCRIPTION = "Minimum like count for a monitored post to be processed.";

    /** Wire literal of {@code backend/app/api/settings.py:L18}. */
    private static final String NO_VALUE_PROVIDED = "No value provided";

    /** Wire literal of {@code backend/app/api/settings.py:L22}. */
    private static final String SETTING_NOT_FOUND = "Setting not found";

    /** Message served with 400 for a body the converter cannot bind — DL-092, DL-188. */
    private static final String BAD_REQUEST = "Bad request";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SettingsService settingsService;

    // backend/app/api/settings.py:L7-11 — array response — DL-039
    @Test
    @DisplayName("renders every row as a JSON array of key, value and description")
    void rendersEveryRowAsAJsonArray() throws Exception {
        when(settingsService.getAllSettings()).thenReturn(List.of(
                new SettingDto(KEY, "100", DESCRIPTION),
                new SettingDto("response_generation_delay", "60",
                        "Seconds between response-generation sweeps."),
                new SettingDto("stream_keywords", "AI coding tool",
                        "Terms the filtered stream tracks.")));

        mockMvc.perform(get("/settings"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].key").value(KEY))
                .andExpect(jsonPath("$[0].value").value("100"))
                .andExpect(jsonPath("$[0].description").value(DESCRIPTION))
                .andExpect(jsonPath("$[1].key").value("response_generation_delay"))
                .andExpect(jsonPath("$[2].key").value("stream_keywords"));

        verify(settingsService).getAllSettings();
    }

    @Test
    @DisplayName("renders each element with exactly the three snake_case members and no other")
    void rendersEachElementWithExactlyThreeMembers() throws Exception {
        when(settingsService.getAllSettings())
                .thenReturn(List.of(new SettingDto(KEY, "100", DESCRIPTION)));

        mockMvc.perform(get("/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].length()").value(3))
                .andExpect(jsonPath("$[0].key").exists())
                .andExpect(jsonPath("$[0].value").exists())
                .andExpect(jsonPath("$[0].description").exists())
                .andExpect(jsonPath("$[0].tweetId").doesNotExist())
                .andExpect(jsonPath("$[0].settingKey").doesNotExist());
    }

    @Test
    @DisplayName("renders an empty array and status 200 when the table holds no row")
    void rendersAnEmptyArrayWhenTheTableHoldsNoRow() throws Exception {
        when(settingsService.getAllSettings()).thenReturn(List.of());

        mockMvc.perform(get("/settings"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]", JsonCompareMode.STRICT));
    }

    // backend/app/api/settings.py:L13-24
    @Test
    @DisplayName("replaces a value and renders the stored row")
    void replacesAValueAndRendersTheStoredRow() throws Exception {
        when(settingsService.updateSetting(KEY, "250"))
                .thenReturn(new SettingDto(KEY, "250", DESCRIPTION));

        mockMvc.perform(put("/settings/{key}", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"250\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$.key").value(KEY))
                .andExpect(jsonPath("$.value").value("250"))
                .andExpect(jsonPath("$.description").value(DESCRIPTION));

        verify(settingsService).updateSetting(KEY, "250");
    }

    @Test
    @DisplayName("passes the path key through unaltered")
    void passesThePathKeyThroughUnaltered() throws Exception {
        when(settingsService.updateSetting("stream_keywords", "Copilot"))
                .thenReturn(new SettingDto("stream_keywords", "Copilot", "Terms."));

        mockMvc.perform(put("/settings/{key}", "stream_keywords")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"Copilot\"}"))
                .andExpect(status().isOk());

        verify(settingsService).updateSetting("stream_keywords", "Copilot");
    }

    // backend/app/api/settings.py:L17 — the guard tests null alone — DL-050
    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(delimiter = '|', value = {
        "\"\"      | ''",
        "0         | 0",
        "false     | false",
        "true      | true",
        "12.5      | 12.5"
    })
    @DisplayName("accepts a value the source guard does not reject for falsiness")
    void acceptsAValueTheSourceGuardDoesNotReject(String jsonValue, String bound) throws Exception {
        when(settingsService.updateSetting(eq(KEY), anyString()))
                .thenAnswer(invocation ->
                        new SettingDto(KEY, invocation.getArgument(1), DESCRIPTION));

        mockMvc.perform(put("/settings/{key}", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":" + jsonValue.trim() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value(bound.trim().replace("''", "")));
    }

    // backend/app/api/settings.py:L17-18
    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"{\"value\":null}", "{}", "{\"other\":\"x\"}"})
    @DisplayName("reports a body carrying no value with 400 and the No value provided literal")
    void reportsABodyCarryingNoValueWith400(String body) throws Exception {
        mockMvc.perform(put("/settings/{key}", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$.error").value(NO_VALUE_PROVIDED));

        verify(settingsService, never()).updateSetting(anyString(), any());
    }

    @Test
    @DisplayName("reports an absent body with 400 and the No value provided literal")
    void reportsAnAbsentBodyWith400() throws Exception {
        when(settingsService.updateSetting(KEY, null))
                .thenThrow(BadRequestException.noValueProvided());

        mockMvc.perform(put("/settings/{key}", KEY)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(NO_VALUE_PROVIDED));
    }

    // backend/app/api/settings.py:L21-22
    @Test
    @DisplayName("reports a key that names no row with 404 and the Setting not found literal")
    void reportsAKeyThatNamesNoRowWith404() throws Exception {
        when(settingsService.updateSetting(ABSENT_KEY, "999"))
                .thenThrow(NotFoundException.settingNotFound());

        mockMvc.perform(put("/settings/{key}", ABSENT_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"999\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$.error").value(SETTING_NOT_FOUND));
    }

    // backend/app/api/settings.py:L17 precedes :L21 — validation before lookup
    @Test
    @DisplayName("validates the body before the key is looked up")
    void validatesTheBodyBeforeTheKeyIsLookedUp() throws Exception {
        mockMvc.perform(put("/settings/{key}", ABSENT_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(NO_VALUE_PROVIDED));

        verifyNoInteractions(settingsService);
    }

    // Net-new (no Python counterpart) — DL-188 — see docs/DECISION_LOG.md
    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
        "{\"value\":\"first\",\"value\":\"second\"}",
        "{\"value\":\"same\",\"value\":\"same\"}",
        "{\"value\":null,\"value\":\"x\"}",
        "{\"value\":\"a\",\"value\":\"b\",\"value\":\"c\"}"
    })
    @DisplayName("reports a body repeating the value member with 400 and never 500")
    void reportsABodyRepeatingTheValueMemberWith400(String body) throws Exception {
        mockMvc.perform(put("/settings/{key}", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$.error").value(BAD_REQUEST));

        verify(settingsService, never()).updateSetting(anyString(), any());
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
        "{\"value\":[\"a\",\"b\"]}",
        "{\"value\":{\"a\":1}}",
        "{\"value\":",
        "42",
        "not json at all"
    })
    @DisplayName("reports a body the converter cannot read with 400 and the Bad request literal")
    void reportsABodyTheConverterCannotReadWith400(String body) throws Exception {
        mockMvc.perform(put("/settings/{key}", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(BAD_REQUEST));

        verify(settingsService, never()).updateSetting(anyString(), any());
    }

    @Test
    @DisplayName("ignores an unknown member and never binds it onto the row")
    void ignoresAnUnknownMemberAndNeverBindsIt() throws Exception {
        when(settingsService.updateSetting(KEY, "7000"))
                .thenReturn(new SettingDto(KEY, "7000", DESCRIPTION));

        mockMvc.perform(put("/settings/{key}", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"7000\",\"key\":\"hijack\",\"description\":\"hijack\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value(KEY))
                .andExpect(jsonPath("$.description").value(DESCRIPTION));

        verify(settingsService).updateSetting(KEY, "7000");
    }

    @Test
    @DisplayName("reports a body whose media type the route does not consume with 415")
    void reportsAnUnsupportedMediaTypeWith415() throws Exception {
        mockMvc.perform(put("/settings/{key}", KEY)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("{\"value\":\"77\"}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error").value("Unsupported media type"));

        verifyNoInteractions(settingsService);
    }

    @Test
    @DisplayName("reports a method the collection path does not support with 405 and an allow header")
    void reportsAnUnsupportedMethodWith405() throws Exception {
        mockMvc.perform(post("/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", "GET"))
                .andExpect(jsonPath("$.error").value("Method not allowed"));

        verifyNoInteractions(settingsService);
    }

    @Test
    @DisplayName("reports an Accept header the route cannot satisfy with 406")
    void reportsAnUnsatisfiableAcceptHeaderWith406() throws Exception {
        when(settingsService.getAllSettings())
                .thenReturn(List.of(new SettingDto(KEY, "100", DESCRIPTION)));

        mockMvc.perform(get("/settings").accept(MediaType.APPLICATION_XML))
                .andExpect(status().isNotAcceptable())
                .andExpect(jsonPath("$.error").value("Not acceptable"));
    }

    // G1 — the routes stay unprefixed: no /api segment and no version segment
    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"/api/settings", "/v1/settings", "/Settings"})
    @DisplayName("serves the collection at no prefixed or differently-cased path")
    void servesTheCollectionAtNoPrefixedPath(String path) throws Exception {
        mockMvc.perform(get(path)).andExpect(status().isNotFound());

        verifyNoInteractions(settingsService);
    }

    @Test
    @DisplayName("reaches the settings table through the service alone")
    void reachesTheSettingsTableThroughTheServiceAlone() throws Exception {
        when(settingsService.getAllSettings())
                .thenReturn(List.of(new SettingDto(KEY, "100", DESCRIPTION)));

        mockMvc.perform(get("/settings")).andExpect(status().isOk());

        verify(settingsService).getAllSettings();
        verify(settingsService, never()).updateSetting(anyString(), any());
        verify(settingsService, never()).seedDefaultSettings();
    }
}
