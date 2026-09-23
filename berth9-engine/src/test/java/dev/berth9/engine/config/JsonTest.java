package dev.berth9.engine.config;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonTest {

    @Test
    void roundTripsNestedStructuresAndKeepsDecimalPrecision() {
        String text = "{\"a\": [1, 2.50, \"x\\\"y\"], \"b\": {\"c\": null, \"d\": true}, \"e\": \"₹ \\u20b9\"}";
        Map<String, Object> parsed = Json.parseObject(text);
        assertEquals(List.of(new BigDecimal("1"), new BigDecimal("2.50"), "x\"y"), parsed.get("a"));
        assertEquals("₹ ₹", parsed.get("e"));
        assertEquals(parsed, Json.parseObject(Json.write(parsed)));
        assertEquals(parsed, Json.parseObject(Json.pretty(parsed)));
    }

    @Test
    void reportsTheLineOfASyntaxError() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Json.parse("{\n\"a\": 1,\n\"b\" 2}"));
        assertTrue(e.getMessage().contains("line 3"));
    }
}
