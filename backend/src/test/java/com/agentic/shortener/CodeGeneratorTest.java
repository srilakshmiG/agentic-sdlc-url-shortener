package com.agentic.shortener;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.HashSet;
import org.junit.jupiter.api.Test;

class CodeGeneratorTest {

    private final CodeGenerator gen = new CodeGenerator();

    @Test
    void generatesUnambiguousFixedLengthCodes() {
        var seen = new HashSet<String>();
        for (int i = 0; i < 5000; i++) {
            String c = gen.next();
            assertEquals(CodeGenerator.LENGTH, c.length());
            assertTrue(c.chars().allMatch(ch -> CodeGenerator.ALPHABET.indexOf(ch) >= 0));
            seen.add(c);
        }
        assertTrue(seen.size() > 4990, "collisions should be very rare");
    }

    @Test
    void validatesAliases() {
        assertTrue(gen.isValidAlias("my-link_1"));
        assertFalse(gen.isValidAlias("ab"));
        assertFalse(gen.isValidAlias("has space"));
        assertFalse(gen.isValidAlias("API"));
        assertFalse(gen.isValidAlias("x".repeat(33)));
        assertFalse(gen.isValidAlias(null));
    }

    @Test
    void expiryIsInclusiveOfTheInstant() {
        Instant now = Instant.parse("2030-01-01T00:00:00Z");
        assertFalse(new Link("abc", "https://e.com", now, null, false, 0).isExpired(now));
        assertFalse(new Link("abc", "https://e.com", now, now.plusSeconds(1), false, 0).isExpired(now));
        assertTrue(new Link("abc", "https://e.com", now, now, false, 0).isExpired(now));
        assertTrue(new Link("abc", "https://e.com", now, now.minusSeconds(1), false, 0).isExpired(now));
    }
}
