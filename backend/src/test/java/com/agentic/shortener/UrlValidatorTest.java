package com.agentic.shortener;

import static org.junit.jupiter.api.Assertions.*;

import com.agentic.shortener.UrlValidator.InvalidUrlException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UrlValidatorTest {

    private final UrlValidator validator = new UrlValidator();

    @Test
    void acceptsHttpAndHttps() {
        assertEquals("https://example.com/a?b=1", validator.validate("  https://example.com/a?b=1 "));
        assertEquals("http://example.org", validator.validate("http://example.org"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "javascript:alert(1)", "file:///etc/passwd", "ftp://example.com/x", "//example.com",
            "http://localhost/admin", "http://127.0.0.1:8080", "http://10.0.0.5/", "http://192.168.1.10/",
            "http://172.16.0.1/", "http://169.254.169.254/latest/meta-data", "http://[::1]/", "http://intranet/",
            "http://user:pass@example.com/", "https://", "not a url"})
    void rejectsUnsafeTargets(String url) {
        assertThrows(InvalidUrlException.class, () -> validator.validate(url));
    }

    @Test
    void rejectsOverlongAndBlank() {
        assertThrows(InvalidUrlException.class, () -> validator.validate("https://example.com/" + "a".repeat(2100)));
        assertThrows(InvalidUrlException.class, () -> validator.validate(" "));
        assertThrows(InvalidUrlException.class, () -> validator.validate(null));
    }

    @Test
    void publicIpsAreAllowed() {
        assertDoesNotThrow(() -> validator.validate("https://93.184.216.34/x"));
        assertDoesNotThrow(() -> validator.validate("http://172.32.0.1/"));
    }
}
