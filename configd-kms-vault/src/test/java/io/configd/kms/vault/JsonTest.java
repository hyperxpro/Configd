package io.configd.kms.vault;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests the hand-rolled {@link Json} reader/writer against the shapes Vault returns. */
class JsonTest {

    @Test
    void parsesNestedObjectAndNavigatesDottedPath() {
        Object root = Json.parse("{\"auth\":{\"client_token\":\"s.abc123\",\"lease_duration\":2764800},"
                + "\"data\":{\"ciphertext\":\"vault:v1:xyz\"}}");
        assertEquals("s.abc123", Json.string(root, "auth.client_token"));
        assertEquals("vault:v1:xyz", Json.string(root, "data.ciphertext"));
        assertNull(Json.string(root, "data.plaintext"), "missing leaf returns null");
        assertNull(Json.string(root, "auth.lease_duration"), "non-string leaf returns null");
        assertNull(Json.string(root, "nope.at.all"), "missing branch returns null");
    }

    @Test
    void parsesArraysBooleansNullsAndEscapes() {
        Object root = Json.parse("{\"errors\":[\"permission denied\",\"1 error\"],\"ok\":true,"
                + "\"x\":null,\"s\":\"a\\\"b\\n\\u0041\"}");
        assertEquals("a\"b\nA", Json.string(root, "s"));
        assertTrue(root instanceof Map<?, ?>);
        Object errors = ((Map<?, ?>) root).get("errors");
        assertEquals(2, ((java.util.List<?>) errors).size());
    }

    @Test
    void writesEscapedFlatObjectSkippingNulls() {
        String json = Json.object("plaintext", "AA==", "associated_data", "bm9kZQ==", "bits", 256, "skip", null);
        Object round = Json.parse(json);
        assertEquals("AA==", Json.string(round, "plaintext"));
        assertEquals("bm9kZQ==", Json.string(round, "associated_data"));
        assertNull(Json.string(round, "skip"), "null value is omitted");
        assertTrue(json.contains("256"), "numbers are emitted bare");
    }

    @Test
    void writerEscapesSpecialCharacters() {
        String json = Json.object("k", "a\"b\\c\n");
        assertEquals("a\"b\\c\n", Json.string(Json.parse(json), "k"));
    }

    @Test
    void malformedInputThrows() {
        assertThrows(Json.JsonException.class, () -> Json.parse("{\"a\":}"));
        assertThrows(Json.JsonException.class, () -> Json.parse("{\"a\":1"));
        assertThrows(Json.JsonException.class, () -> Json.parse("not json"));
        assertThrows(Json.JsonException.class, () -> Json.parse("{} trailing"));
    }
}
