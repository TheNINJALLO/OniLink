package dev.onistone.onilink.geyser;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TopLevelJsonTest {
    @Test
    void extractsOnlyTheTopLevelClaim() {
        String json = "{\"nested\":{\"OniForward\":\"wrong\"},\"array\":[1,true,null],\"OniForward\":\"abc.def\"}";
        assertEquals("abc.def", TopLevelJson.uniqueString(json, "OniForward"));
    }

    @Test
    void handlesEscapesAndRejectsDuplicateOrNonStringClaims() {
        assertEquals("abc.def", TopLevelJson.uniqueString("{\"Oni\\u0046orward\":\"abc.def\"}", "OniForward"));
        assertThrows(IllegalArgumentException.class,
                () -> TopLevelJson.uniqueString("{\"OniForward\":\"a\",\"OniForward\":\"b\"}", "OniForward"));
        assertThrows(IllegalArgumentException.class,
                () -> TopLevelJson.uniqueString("{\"OniForward\":123}", "OniForward"));
        assertThrows(IllegalArgumentException.class,
                () -> TopLevelJson.uniqueString("{\"Other\":\"value\"}", "OniForward"));
    }

    @Test
    void rejectsMalformedJson() {
        assertThrows(IllegalArgumentException.class,
                () -> TopLevelJson.uniqueString("{\"OniForward\":\"abc.def\",}", "OniForward"));
        assertThrows(IllegalArgumentException.class,
                () -> TopLevelJson.uniqueString("{\"OniForward\":\"abc.def\"} garbage", "OniForward"));
    }
}
