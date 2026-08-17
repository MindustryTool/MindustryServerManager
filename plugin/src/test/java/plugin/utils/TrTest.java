package plugin.utils;

import java.util.Locale;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TrTest {
    @Test
    void absentKeyReturnsInterpolatedFallback() {
        Tr.catalog().load("en", "{\"welcome\": {\"message\": \"Welcome\"}}", null);

        assertEquals("Hello acme", Tr.tWithFallback(Locale.ENGLISH, "no.such.key",
                "Hello {server}", "server", "acme"));
    }

    @Test
    void presentKeyWinsOverFallback() {
        Tr.catalog().load("en", "{\"welcome\": {\"message\": \"Welcome\"}}", null);

        assertEquals("Welcome", Tr.tWithFallback(Locale.ENGLISH, "welcome.message", "Fallback"));
    }
}