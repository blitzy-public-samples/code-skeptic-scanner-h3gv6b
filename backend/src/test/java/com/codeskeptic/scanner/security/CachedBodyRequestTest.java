package com.codeskeptic.scanner.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.BufferedReader;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

// Net-new (no Python counterpart) — DL-118 — see docs/DECISION_LOG.md
/**
 * Verifies the charset the request wrapper decodes the cached login body with.
 *
 * <p>The wrapper is a private nested class of {@link SecurityConfig} and is reached reflectively; its
 * declared visibility is unchanged.
 */
class CachedBodyRequestTest {

    private static final String BODY = "{\"username\":\"admin\",\"password\":\"caf\u00e9\"}";

    @Test
    @DisplayName("decodes the cached body as UTF-8 when the request declares no charset")
    void decodesTheCachedBodyAsUtf8WhenTheRequestDeclaresNoCharset() throws Exception {
        HttpServletRequestWrapper wrapper = wrap(requestDeclaring(null));

        assertThat(readFully(wrapper)).isEqualTo(BODY);
    }

    @Test
    @DisplayName("decodes the cached body with the declared charset when it is usable")
    void decodesTheCachedBodyWithTheDeclaredCharsetWhenItIsUsable() throws Exception {
        HttpServletRequestWrapper wrapper = wrap(requestDeclaring("UTF-8"));

        assertThat(readFully(wrapper)).isEqualTo(BODY);
    }

    // Charset.forName throws UnsupportedCharsetException and IllegalCharsetNameException, both
    // unchecked — DL-118 — see docs/DECISION_LOG.md
    @ParameterizedTest(name = "[{index}] \"{0}\"")
    @ValueSource(strings = {
        "not-a-charset",
        "UTF-99",
        "utf 8",
        "\"UTF-8\"",
        "=",
        " ",
    })
    @DisplayName("falls back to UTF-8 when the declared charset cannot be used")
    void fallsBackToUtf8WhenTheDeclaredCharsetCannotBeUsed(String declared) throws Exception {
        HttpServletRequestWrapper wrapper = wrap(requestDeclaring(declared));

        assertThatCode(() -> readFully(wrapper)).doesNotThrowAnyException();
        assertThat(readFully(wrapper)).isEqualTo(BODY);
    }

    private static MockHttpServletRequest requestDeclaring(String characterEncoding) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/token");
        request.setContent(BODY.getBytes(StandardCharsets.UTF_8));
        if (characterEncoding != null) {
            request.setCharacterEncoding(characterEncoding);
        }
        return request;
    }

    /**
     * Builds the private wrapper over the supplied request and the UTF-8 encoding of {@link #BODY}.
     *
     * @param request the request to wrap
     * @return the wrapper under test
     * @throws ReflectiveOperationException when the nested type or its constructor cannot be reached
     */
    private static HttpServletRequestWrapper wrap(MockHttpServletRequest request)
            throws ReflectiveOperationException {

        Class<?> type = Class.forName(
                "com.codeskeptic.scanner.security.SecurityConfig$CachedBodyRequest");
        Constructor<?> constructor =
                type.getDeclaredConstructor(HttpServletRequest.class, byte[].class);
        constructor.setAccessible(true);
        return (HttpServletRequestWrapper) constructor.newInstance(
                request, BODY.getBytes(StandardCharsets.UTF_8));
    }

    private static String readFully(HttpServletRequestWrapper wrapper) throws Exception {
        try (BufferedReader reader = wrapper.getReader()) {
            StringBuilder text = new StringBuilder();
            int character;
            while ((character = reader.read()) != -1) {
                text.append((char) character);
            }
            return text.toString();
        }
    }
}
