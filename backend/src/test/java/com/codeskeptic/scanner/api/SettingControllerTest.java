package com.codeskeptic.scanner.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultMatcher;

import com.codeskeptic.scanner.config.CorsConfig;
import com.codeskeptic.scanner.config.ScannerProperties;
import com.codeskeptic.scanner.dto.SettingDto;
import com.codeskeptic.scanner.exception.BadRequestException;
import com.codeskeptic.scanner.exception.NotFoundException;
import com.codeskeptic.scanner.security.JwtService;
import com.codeskeptic.scanner.security.SecurityConfig;
import com.codeskeptic.scanner.service.SettingsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

// Ported from backend/tests/test_api.py:L36-44 (faithful port) — see docs/DECISION_LOG.md
/**
 * Exercises the two routes {@link SettingController} serves — {@code GET /settings} and
 * {@code PUT /settings/{key}} — through {@link MockMvc}.
 *
 * <p>The slice registers {@link SettingController}, the real {@link SecurityConfig} filter chain, the
 * real {@link CorsConfig} policy, the real {@link JwtService} and the bound {@link ScannerProperties},
 * under the {@code test} profile of {@code src/test/resources/application-test.yml}.
 * {@link GlobalExceptionHandler} is a {@code @RestControllerAdvice} and is part of every web slice.
 * {@link SettingsService} is the one mocked collaborator. The slice opens no database and reads no
 * external credential.
 *
 * <p>The contract asserted here is the contract of {@code backend/app/api/settings.py}:
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
 * <tr><td>the read fails</td><td>500</td>
 *     <td>{@code {"error":"Internal server error"}}</td>
 *     <td>{@code backend/app/main.py:L35-37}</td></tr>
 * <tr><td>no authenticated principal</td><td>401</td><td>empty</td>
 *     <td>net-new — DL-021</td></tr>
 * <tr><td>body the converter cannot bind</td><td>400</td>
 *     <td>{@code {"error":"Bad request"}}</td><td>net-new — DL-092, DL-188</td></tr>
 * </table>
 *
 * <p>Decisions covered by the assertions here are recorded in {@code docs/DECISION_LOG.md} DL-021,
 * DL-039, DL-043, DL-048, DL-050, DL-092 and DL-188.
 */
@WebMvcTest(SettingController.class)
@ActiveProfiles("test")
@Import({ SecurityConfig.class, CorsConfig.class, JwtService.class })
@EnableConfigurationProperties(ScannerProperties.class)
@DisplayName("SettingController")
class SettingControllerTest {

    /** Principal named by {@code scanner.auth.username} under the {@code test} profile. */
    private static final String PRINCIPAL = "admin";

    /** Key of the row every positive case addresses. */
    private static final String KEY = "tweet_popularity_threshold";

    /** Key that names no row. */
    private static final String ABSENT_KEY = "does_not_exist";

    /** Key spelled in the retired suite at {@code backend/tests/test_api.py:L39}. */
    private static final String UNDERSCORED_KEY = "auto_response";

    /** Key spelled with digits alone. */
    private static final String NUMERIC_KEY = "123";

    /** Key spelled with a hyphen. */
    private static final String HYPHENATED_KEY = "stream-keywords";

    /** Description carried by the row {@link #KEY} names. */
    private static final String DESCRIPTION = "Minimum like count for a monitored post to be processed.";

    /** Wire literal of {@code backend/app/api/settings.py:L18}. */
    private static final String NO_VALUE_PROVIDED = "No value provided";

    /** Wire literal of {@code backend/app/api/settings.py:L22}. */
    private static final String SETTING_NOT_FOUND = "Setting not found";

    /** Wire literal of {@code backend/app/main.py:L37}. */
    private static final String INTERNAL_SERVER_ERROR = "Internal server error";

    /** Message served with 400 for a body the converter cannot bind — DL-092, DL-188. */
    private static final String BAD_REQUEST = "Bad request";

    /** The sanctioned envelope of an unmatched path — backend/app/main.py:L31-33, DL-183. */
    private static final String NOT_FOUND_BODY = "{\"error\":\"Not found\"}";

    /** Members a {@code ProblemDetail} body carries; none of them reaches the wire. */
    private static final List<String> PROBLEM_DETAIL_MEMBERS =
            List.of("type", "title", "status", "detail", "instance", "errors");

    /** Reads the response body of a case that asserts the parsed JSON shape. */
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private SettingsService settingsService;

    // -------------------------------------------------------------------------
    // GET /settings — backend/app/api/settings.py:L7-11 — see docs/DECISION_LOG.md DL-039
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("renders every row as a JSON array of key, value and description")
    void rendersEveryRowAsAJsonArray() throws Exception {
        when(settingsService.getAllSettings()).thenReturn(List.of(
                new SettingDto(KEY, "100", DESCRIPTION),
                new SettingDto("response_generation_delay", "60",
                        "Seconds between response-generation sweeps."),
                new SettingDto("stream_keywords", "AI coding tool",
                        "Terms the filtered stream tracks.")));

        mockMvc.perform(get("/settings").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].key").value(KEY))
                .andExpect(jsonPath("$[0].value").value("100"))
                .andExpect(jsonPath("$[0].description").value(DESCRIPTION))
                .andExpect(jsonPath("$[1].key").value("response_generation_delay"))
                .andExpect(jsonPath("$[1].value").value("60"))
                .andExpect(jsonPath("$[1].description").value("Seconds between response-generation sweeps."))
                .andExpect(jsonPath("$[2].key").value("stream_keywords"))
                .andExpect(jsonPath("$[2].value").value("AI coding tool"))
                .andExpect(jsonPath("$[2].description").value("Terms the filtered stream tracks."));

        verify(settingsService, times(1)).getAllSettings();
        verifyNoMoreInteractions(settingsService);
    }

    @Test
    @DisplayName("renders the root as a JSON array and never as a JSON object")
    void rendersTheRootAsAJsonArrayAndNeverAsAJsonObject() throws Exception {
        when(settingsService.getAllSettings()).thenReturn(List.of(
                new SettingDto(KEY, "100", DESCRIPTION),
                new SettingDto(UNDERSCORED_KEY, "true", "Whether responses post themselves.")));

        MvcResult result = mockMvc.perform(get("/settings")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode root = JSON.readTree(result.getResponse().getContentAsString());
        assertThat(root.isArray()).isTrue();
        assertThat(root.isObject()).isFalse();
        assertThat(root.size()).isEqualTo(2);
        assertThat(root.get(0).isObject()).isTrue();
        assertThat(root.get(1).isObject()).isTrue();
    }

    @Test
    @DisplayName("renders each element with exactly the three snake_case members and no other")
    void rendersEachElementWithExactlyThreeMembers() throws Exception {
        when(settingsService.getAllSettings()).thenReturn(List.of(
                new SettingDto(KEY, "100", DESCRIPTION),
                new SettingDto(UNDERSCORED_KEY, "true", "Whether responses post themselves.")));

        MvcResult result = mockMvc.perform(get("/settings")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].length()").value(3))
                .andExpect(jsonPath("$[0].key").exists())
                .andExpect(jsonPath("$[0].value").exists())
                .andExpect(jsonPath("$[0].description").exists())
                .andExpect(jsonPath("$[1].length()").value(3))
                .andExpect(jsonPath("$[1].key").exists())
                .andExpect(jsonPath("$[1].value").exists())
                .andExpect(jsonPath("$[1].description").exists())
                .andExpect(jsonPath("$[0].tweetId").doesNotExist())
                .andExpect(jsonPath("$[0].settingKey").doesNotExist())
                .andReturn();

        JsonNode root = JSON.readTree(result.getResponse().getContentAsString());
        for (JsonNode element : root) {
            assertThat(element.size()).isEqualTo(3);
            assertThat(element.has("key")).isTrue();
            assertThat(element.has("value")).isTrue();
            assertThat(element.has("description")).isTrue();
        }
    }

    // backend/tests/test_api.py:L39 — see docs/DECISION_LOG.md DL-039
    @Test
    @DisplayName("carries no member keyed by a setting name")
    void carriesNoMemberKeyedByASettingName() throws Exception {
        when(settingsService.getAllSettings()).thenReturn(List.of(
                new SettingDto(UNDERSCORED_KEY, "true", "Whether responses post themselves."),
                new SettingDto(KEY, "100", DESCRIPTION)));

        MvcResult result = mockMvc.perform(get("/settings")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$." + UNDERSCORED_KEY).doesNotExist())
                .andExpect(jsonPath("$." + KEY).doesNotExist())
                .andReturn();

        JsonNode root = JSON.readTree(result.getResponse().getContentAsString());
        assertThat(root.isObject()).isFalse();
        assertThat(root.get(UNDERSCORED_KEY)).isNull();
        assertThat(root.get(KEY)).isNull();
        assertThat(root.get(0).get("key").textValue()).isEqualTo(UNDERSCORED_KEY);
        assertThat(root.get(0).get("value").textValue()).isEqualTo("true");
    }

    @Test
    @DisplayName("renders an empty array and status 200 when the table holds no row")
    void rendersAnEmptyArrayWhenTheTableHoldsNoRow() throws Exception {
        when(settingsService.getAllSettings()).thenReturn(List.of());

        MvcResult result = mockMvc.perform(get("/settings")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(content().json("[]", JsonCompareMode.STRICT))
                .andExpect(content().string("[]"))
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$", hasSize(0)))
                .andReturn();

        JsonNode root = JSON.readTree(result.getResponse().getContentAsString());
        assertThat(root.isArray()).isTrue();
        assertThat(root.isObject()).isFalse();
        assertThat(root.isNull()).isFalse();
        assertThat(root.isEmpty()).isTrue();
    }

    // backend/app/api/settings.py:L10 — see docs/DECISION_LOG.md DL-043
    @Test
    @DisplayName("reads the table once through the injected service and passes no argument")
    void readsTheTableOnceThroughTheInjectedService() throws Exception {
        when(settingsService.getAllSettings())
                .thenReturn(List.of(new SettingDto(KEY, "100", DESCRIPTION)));

        mockMvc.perform(get("/settings").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk());

        verify(settingsService, times(1)).getAllSettings();
        verify(settingsService, never()).updateSetting(anyString(), any());
        verifyNoMoreInteractions(settingsService);
    }

    // backend/app/api/settings.py:L7 — the registered path carries no trailing slash
    @Test
    @DisplayName("serves nothing at the collection path spelled with a trailing slash")
    void servesNothingAtTheCollectionPathSpelledWithATrailingSlash() throws Exception {
        mockMvc.perform(get("/settings/").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().string(NOT_FOUND_BODY));

        verifyNoInteractions(settingsService);
    }

    // G1 — the routes stay unprefixed: no /api segment and no version segment
    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"/api/settings", "/v1/settings", "/Settings"})
    @DisplayName("serves the collection at no prefixed or differently-cased path")
    void servesTheCollectionAtNoPrefixedPath(String path) throws Exception {
        mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().string(NOT_FOUND_BODY));

        verifyNoInteractions(settingsService);
    }

    // backend/app/api/settings.py:L8 — see docs/DECISION_LOG.md DL-021
    @Test
    @DisplayName("answers a read carrying no credential with 401 and an empty body")
    void answersAReadCarryingNoCredentialWith401AndAnEmptyBody() throws Exception {
        MvcResult result = mockMvc.perform(get("/settings"))
                .andExpect(status().isUnauthorized())
                .andExpect(statusIsNot(HttpStatus.FORBIDDEN.value()))
                .andExpect(content().string(""))
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).isEmpty();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("error");
        verifyNoInteractions(settingsService);
    }

    // backend/app/main.py:L35-37
    @Test
    @DisplayName("reports a failed read with 500 and the Internal server error literal")
    void reportsAFailedReadWith500() throws Exception {
        when(settingsService.getAllSettings()).thenThrow(new RuntimeException("read failed"));

        mockMvc.perform(get("/settings").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isInternalServerError())
                .andExpect(content().json("{\"error\":\"" + INTERNAL_SERVER_ERROR + "\"}",
                        JsonCompareMode.STRICT))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$.error").value(INTERNAL_SERVER_ERROR));

        verify(settingsService).getAllSettings();
    }

    @Test
    @DisplayName("serves the read to an authenticated principal")
    void servesTheReadToAnAuthenticatedPrincipal() throws Exception {
        when(settingsService.getAllSettings())
                .thenReturn(List.of(new SettingDto(KEY, "100", DESCRIPTION)));

        mockMvc.perform(get("/settings").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].key").value(KEY));

        verify(settingsService).getAllSettings();
    }

    // -------------------------------------------------------------------------
    // PUT /settings/{key} — backend/app/api/settings.py:L13-24
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("replaces a value and renders the stored row")
    void replacesAValueAndRendersTheStoredRow() throws Exception {
        when(settingsService.updateSetting(KEY, "250"))
                .thenReturn(new SettingDto(KEY, "250", DESCRIPTION));

        mockMvc.perform(put("/settings/{key}", KEY)
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"250\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$.key").value(KEY))
                .andExpect(jsonPath("$.value").value("250"))
                .andExpect(jsonPath("$.description").value(DESCRIPTION));

        verify(settingsService).updateSetting(KEY, "250");
    }

    // backend/app/api/settings.py:L13,L16,L20 — see docs/DECISION_LOG.md DL-043, DL-048
    @Test
    @DisplayName("passes the path key and the body value to the service verbatim")
    void passesThePathKeyAndTheBodyValueToTheServiceVerbatim() throws Exception {
        when(settingsService.updateSetting(anyString(), any()))
                .thenReturn(new SettingDto(KEY, "250", DESCRIPTION));

        mockMvc.perform(put("/settings/{key}", KEY)
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"250\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(settingsService, times(1)).updateSetting(key.capture(), value.capture());

        assertThat(key.getValue()).isEqualTo(KEY);
        assertThat(value.getValue()).isEqualTo("250");
        assertThat((Object) key.getValue()).isInstanceOf(String.class);
        assertThat((Object) value.getValue()).isInstanceOf(String.class);
        verifyNoMoreInteractions(settingsService);
    }

    // backend/app/api/settings.py:L17-18 — see docs/DECISION_LOG.md DL-050
    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"{\"value\":null}", "{}", "{\"other\":\"x\"}"})
    @DisplayName("reports a body carrying no value with 400 and the No value provided literal")
    void reportsABodyCarryingNoValueWith400(String body) throws Exception {
        mockMvc.perform(put("/settings/{key}", KEY)
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"error\":\"" + NO_VALUE_PROVIDED + "\"}",
                        JsonCompareMode.STRICT))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$.error").value(NO_VALUE_PROVIDED))
                .andExpect(jsonPath("$.value").doesNotExist())
                .andExpect(jsonPath("$.field").doesNotExist())
                .andExpect(jsonPath("$.message").doesNotExist())
                .andExpect(jsonPath("$.timestamp").doesNotExist())
                .andExpect(carriesNoProblemDetailMember())
                .andExpect(content().string(not(containsString("must not be null"))))
                .andExpect(content().string(not(containsString("NotNull"))))
                .andExpect(content().string(not(containsString("updateSetting"))))
                .andExpect(carriesOnlyTheErrorMember());

        verify(settingsService, never()).updateSetting(anyString(), any());
        verifyNoInteractions(settingsService);
    }

    // backend/app/api/settings.py:L17 — see docs/DECISION_LOG.md DL-050
    @Test
    @DisplayName("accepts the empty string as a value")
    void acceptsTheEmptyStringAsAValue() throws Exception {
        when(settingsService.updateSetting(eq(KEY), anyString()))
                .thenAnswer(invocation -> new SettingDto(KEY, invocation.getArgument(1), DESCRIPTION));

        mockMvc.perform(put("/settings/{key}", KEY)
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(statusIsNot(HttpStatus.BAD_REQUEST.value()))
                .andExpect(jsonPath("$.value").value(""));

        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(settingsService).updateSetting(eq(KEY), value.capture());
        assertThat(value.getValue()).isNotNull();
        assertThat(value.getValue()).isEmpty();
    }

    @Test
    @DisplayName("accepts a JSON boolean as a value")
    void acceptsAJsonBooleanAsAValue() throws Exception {
        when(settingsService.updateSetting(eq(KEY), anyString()))
                .thenAnswer(invocation -> new SettingDto(KEY, invocation.getArgument(1), DESCRIPTION));

        mockMvc.perform(put("/settings/{key}", KEY)
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":false}"))
                .andExpect(status().isOk())
                .andExpect(statusIsNot(HttpStatus.BAD_REQUEST.value()))
                .andExpect(jsonPath("$.value").value("false"));

        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(settingsService).updateSetting(eq(KEY), value.capture());
        assertThat(value.getValue()).isNotNull();
        assertThat(value.getValue()).isEqualTo("false");
    }

    @Test
    @DisplayName("accepts a JSON number as a value")
    void acceptsAJsonNumberAsAValue() throws Exception {
        when(settingsService.updateSetting(eq(KEY), anyString()))
                .thenAnswer(invocation -> new SettingDto(KEY, invocation.getArgument(1), DESCRIPTION));

        mockMvc.perform(put("/settings/{key}", KEY)
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":0}"))
                .andExpect(status().isOk())
                .andExpect(statusIsNot(HttpStatus.BAD_REQUEST.value()))
                .andExpect(jsonPath("$.value").value("0"));

        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(settingsService).updateSetting(eq(KEY), value.capture());
        assertThat(value.getValue()).isNotNull();
        assertThat(value.getValue()).isEqualTo("0");
    }

    @ParameterizedTest(name = "[{index}] {0} -> {1}")
    @CsvSource(delimiter = '|', value = {
        "true      | true",
        "12.5      | 12.5",
        "-1        | -1",
        "0.0       | 0.0"
    })
    @DisplayName("accepts a scalar value and passes its text to the service")
    void acceptsAScalarValueAndPassesItsTextToTheService(String jsonValue, String bound)
            throws Exception {

        when(settingsService.updateSetting(eq(KEY), anyString()))
                .thenAnswer(invocation -> new SettingDto(KEY, invocation.getArgument(1), DESCRIPTION));

        mockMvc.perform(put("/settings/{key}", KEY)
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":" + jsonValue.trim() + "}"))
                .andExpect(status().isOk())
                .andExpect(statusIsNot(HttpStatus.BAD_REQUEST.value()))
                .andExpect(jsonPath("$.value").value(bound.trim()));

        verify(settingsService).updateSetting(KEY, bound.trim());
    }

    // backend/app/api/settings.py:L21-22
    @Test
    @DisplayName("reports a key that names no row with 404 and the Setting not found literal")
    void reportsAKeyThatNamesNoRowWith404() throws Exception {
        when(settingsService.updateSetting(ABSENT_KEY, "999"))
                .thenThrow(NotFoundException.settingNotFound());

        mockMvc.perform(put("/settings/{key}", ABSENT_KEY)
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"999\"}"))
                .andExpect(status().isNotFound())
                .andExpect(content().json("{\"error\":\"" + SETTING_NOT_FOUND + "\"}",
                        JsonCompareMode.STRICT))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$.error").value(SETTING_NOT_FOUND))
                .andExpect(carriesNoProblemDetailMember());

        verify(settingsService).updateSetting(ABSENT_KEY, "999");
    }

    // backend/app/api/settings.py:L13 — see docs/DECISION_LOG.md DL-048
    @Test
    @DisplayName("reports an underscored key that names no row with 404")
    void reportsAnUnderscoredKeyThatNamesNoRowWith404() throws Exception {
        when(settingsService.updateSetting(anyString(), any()))
                .thenThrow(NotFoundException.settingNotFound());

        mockMvc.perform(put("/settings/{key}", UNDERSCORED_KEY)
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"true\"}"))
                .andExpect(status().isNotFound())
                .andExpect(statusIsNot(HttpStatus.BAD_REQUEST.value()))
                .andExpect(statusIsNot(HttpStatus.INTERNAL_SERVER_ERROR.value()))
                .andExpect(jsonPath("$.error").value(SETTING_NOT_FOUND));

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(settingsService).updateSetting(key.capture(), any());
        assertThat(key.getValue()).isEqualTo(UNDERSCORED_KEY);
        assertThat((Object) key.getValue()).isInstanceOf(String.class);
    }

    @Test
    @DisplayName("passes a key spelled with digits alone through as a String")
    void passesAKeySpelledWithDigitsAloneThroughAsAString() throws Exception {
        when(settingsService.updateSetting(anyString(), any()))
                .thenReturn(new SettingDto(NUMERIC_KEY, "7", DESCRIPTION));

        mockMvc.perform(put("/settings/{key}", NUMERIC_KEY)
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"7\"}"))
                .andExpect(status().isOk())
                .andExpect(statusIsNot(HttpStatus.BAD_REQUEST.value()))
                .andExpect(statusIsNot(HttpStatus.INTERNAL_SERVER_ERROR.value()));

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(settingsService).updateSetting(key.capture(), any());
        assertThat((Object) key.getValue()).isInstanceOf(String.class);
        assertThat(key.getValue()).isEqualTo("123");
    }

    @Test
    @DisplayName("passes a hyphenated key through as a String")
    void passesAHyphenatedKeyThroughAsAString() throws Exception {
        when(settingsService.updateSetting(anyString(), any()))
                .thenReturn(new SettingDto(HYPHENATED_KEY, "Copilot", "Terms."));

        mockMvc.perform(put("/settings/{key}", HYPHENATED_KEY)
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"Copilot\"}"))
                .andExpect(status().isOk())
                .andExpect(statusIsNot(HttpStatus.BAD_REQUEST.value()));

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(settingsService).updateSetting(key.capture(), any());
        assertThat((Object) key.getValue()).isInstanceOf(String.class);
        assertThat(key.getValue()).isEqualTo(HYPHENATED_KEY);
    }

    @Test
    @DisplayName("reports a hyphenated key that names no row with 404")
    void reportsAHyphenatedKeyThatNamesNoRowWith404() throws Exception {
        when(settingsService.updateSetting(anyString(), any()))
                .thenThrow(NotFoundException.settingNotFound());

        mockMvc.perform(put("/settings/{key}", HYPHENATED_KEY)
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"Copilot\"}"))
                .andExpect(status().isNotFound())
                .andExpect(statusIsNot(HttpStatus.BAD_REQUEST.value()))
                .andExpect(statusIsNot(HttpStatus.INTERNAL_SERVER_ERROR.value()))
                .andExpect(jsonPath("$.error").value(SETTING_NOT_FOUND));
    }

    // backend/app/api/settings.py:L17 precedes :L21
    @Test
    @DisplayName("validates the body before the key is looked up")
    void validatesTheBodyBeforeTheKeyIsLookedUp() throws Exception {
        mockMvc.perform(put("/settings/{key}", ABSENT_KEY)
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(NO_VALUE_PROVIDED));

        verifyNoInteractions(settingsService);
    }

    @Test
    @DisplayName("reports an absent body with 400 and the No value provided literal")
    void reportsAnAbsentBodyWith400() throws Exception {
        when(settingsService.updateSetting(KEY, null))
                .thenThrow(BadRequestException.noValueProvided());

        mockMvc.perform(put("/settings/{key}", KEY)
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"error\":\"" + NO_VALUE_PROVIDED + "\"}",
                        JsonCompareMode.STRICT))
                .andExpect(jsonPath("$.error").value(NO_VALUE_PROVIDED));
    }

    // backend/app/main.py:L35-37
    @Test
    @DisplayName("reports a failed update with 500 and the Internal server error literal")
    void reportsAFailedUpdateWith500() throws Exception {
        when(settingsService.updateSetting(KEY, "250"))
                .thenThrow(new RuntimeException("write failed"));

        mockMvc.perform(put("/settings/{key}", KEY)
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"250\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().json("{\"error\":\"" + INTERNAL_SERVER_ERROR + "\"}",
                        JsonCompareMode.STRICT))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$.error").value(INTERNAL_SERVER_ERROR))
                .andExpect(carriesOnlyTheErrorMember());

        verify(settingsService).updateSetting(KEY, "250");
    }

    // backend/app/api/settings.py:L14 — see docs/DECISION_LOG.md DL-021
    @Test
    @DisplayName("answers an update carrying no credential with 401 and an empty body")
    void answersAnUpdateCarryingNoCredentialWith401AndAnEmptyBody() throws Exception {
        MvcResult result = mockMvc.perform(put("/settings/{key}", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"250\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(statusIsNot(HttpStatus.FORBIDDEN.value()))
                .andExpect(content().string(""))
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).isEmpty();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("error");
        verifyNoInteractions(settingsService);
    }

    // backend/app/api/settings.py:L13 — the registered path carries a key segment
    @Test
    @DisplayName("serves nothing at the update path spelled with a trailing slash and no key")
    void servesNothingAtTheUpdatePathSpelledWithATrailingSlashAndNoKey() throws Exception {
        mockMvc.perform(put("/settings/")
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"250\"}"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().string(NOT_FOUND_BODY));

        verifyNoInteractions(settingsService);
    }

    @Test
    @DisplayName("serves the update to an authenticated principal")
    void servesTheUpdateToAnAuthenticatedPrincipal() throws Exception {
        when(settingsService.updateSetting(KEY, "250"))
                .thenReturn(new SettingDto(KEY, "250", DESCRIPTION));

        mockMvc.perform(put("/settings/{key}", KEY)
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"250\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value(KEY))
                .andExpect(jsonPath("$.value").value("250"))
                .andExpect(jsonPath("$.description").value(DESCRIPTION));

        verify(settingsService).updateSetting(KEY, "250");
    }

    // -------------------------------------------------------------------------
    // Validation surface — backend/app/schema/tweet.py:L5-14 declares types and optionality only —
    // see docs/DECISION_LOG.md DL-050
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("accepts a value of ten thousand characters")
    void acceptsAValueOfTenThousandCharacters() throws Exception {
        String longValue = "x".repeat(10_000);
        when(settingsService.updateSetting(eq(KEY), anyString()))
                .thenAnswer(invocation -> new SettingDto(KEY, invocation.getArgument(1), DESCRIPTION));

        mockMvc.perform(put("/settings/{key}", KEY)
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"" + longValue + "\"}"))
                .andExpect(status().isOk())
                .andExpect(statusIsNot(HttpStatus.BAD_REQUEST.value()));

        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(settingsService).updateSetting(eq(KEY), value.capture());
        assertThat(value.getValue()).hasSize(10_000);
    }

    @Test
    @DisplayName("accepts a value of one character")
    void acceptsAValueOfOneCharacter() throws Exception {
        when(settingsService.updateSetting(eq(KEY), anyString()))
                .thenAnswer(invocation -> new SettingDto(KEY, invocation.getArgument(1), DESCRIPTION));

        mockMvc.perform(put("/settings/{key}", KEY)
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"7\"}"))
                .andExpect(status().isOk())
                .andExpect(statusIsNot(HttpStatus.BAD_REQUEST.value()))
                .andExpect(jsonPath("$.value").value("7"));

        verify(settingsService).updateSetting(KEY, "7");
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"not-a-number", " ", "  spaced  ", "1e3", "0x10", "∞", "null", "NaN"})
    @DisplayName("accepts a value that names no number for a key spelled with digits alone")
    void acceptsAValueThatNamesNoNumberForANumericKey(String value) throws Exception {
        when(settingsService.updateSetting(anyString(), anyString()))
                .thenAnswer(invocation -> new SettingDto(invocation.getArgument(0),
                        invocation.getArgument(1), DESCRIPTION));

        mockMvc.perform(put("/settings/{key}", NUMERIC_KEY)
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("value", value))))
                .andExpect(status().isOk())
                .andExpect(statusIsNot(HttpStatus.BAD_REQUEST.value()))
                .andExpect(jsonPath("$.value").value(value));

        verify(settingsService).updateSetting(NUMERIC_KEY, value);
    }

    // -------------------------------------------------------------------------
    // Request-binding surface — net-new (no Python counterpart) — see docs/DECISION_LOG.md DL-092,
    // DL-188
    // -------------------------------------------------------------------------

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
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(statusIsNot(HttpStatus.INTERNAL_SERVER_ERROR.value()))
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
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(statusIsNot(HttpStatus.INTERNAL_SERVER_ERROR.value()))
                .andExpect(jsonPath("$.error").value(BAD_REQUEST));

        verify(settingsService, never()).updateSetting(anyString(), any());
    }

    @Test
    @DisplayName("ignores an unknown member and never binds it onto the row")
    void ignoresAnUnknownMemberAndNeverBindsIt() throws Exception {
        when(settingsService.updateSetting(KEY, "7000"))
                .thenReturn(new SettingDto(KEY, "7000", DESCRIPTION));

        mockMvc.perform(put("/settings/{key}", KEY)
                        .header(HttpHeaders.AUTHORIZATION, bearer())
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
                        .header(HttpHeaders.AUTHORIZATION, bearer())
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
                        .header(HttpHeaders.AUTHORIZATION, bearer())
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

        mockMvc.perform(get("/settings")
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .accept(MediaType.APPLICATION_XML))
                .andExpect(status().isNotAcceptable())
                .andExpect(jsonPath("$.error").value("Not acceptable"));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Builds the {@code Authorization} header value of an authenticated request.
     *
     * <p>The token is minted by the same {@code security/JwtService} the imported
     * {@code security/SecurityConfig} chain verifies, so every request carrying it crosses
     * {@code security/JwtAuthenticationFilter} — DL-021, DL-115.
     *
     * @return the {@code Bearer} credential of the principal {@value #PRINCIPAL}
     */
    private String bearer() {
        return "Bearer " + jwtService.generateToken(PRINCIPAL);
    }

    /**
     * Builds a matcher asserting that the response status differs from {@code status}.
     *
     * @param status the status the response must not carry
     * @return the matcher; never {@code null}
     */
    private static ResultMatcher statusIsNot(int status) {
        return result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(status);
    }

    /**
     * Builds a matcher asserting that the response body carries none of
     * {@link #PROBLEM_DETAIL_MEMBERS}.
     *
     * @return the matcher; never {@code null}
     */
    private static ResultMatcher carriesNoProblemDetailMember() {
        return result -> {
            JsonNode root = JSON.readTree(result.getResponse().getContentAsString());
            for (String member : PROBLEM_DETAIL_MEMBERS) {
                assertThat(root.has(member)).isFalse();
            }
            assertThat(root.size()).isEqualTo(1);
            assertThat(root.has("error")).isTrue();
        };
    }

    /**
     * Builds a matcher asserting that the response body carries the member {@code error} and no
     * member named after a request field.
     *
     * @return the matcher; never {@code null}
     */
    private static ResultMatcher carriesOnlyTheErrorMember() {
        return result -> {
            JsonNode root = JSON.readTree(result.getResponse().getContentAsString());
            assertThat(root.isObject()).isTrue();
            assertThat(root.size()).isEqualTo(1);
            assertThat(root.has("error")).isTrue();
            assertThat(root.has("value")).isFalse();
            assertThat(root.has("key")).isFalse();
            assertThat(root.has("description")).isFalse();
            assertThat(root.get("error").isTextual()).isTrue();
        };
    }
}
