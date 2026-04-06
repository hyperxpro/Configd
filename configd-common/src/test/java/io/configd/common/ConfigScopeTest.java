package io.configd.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConfigScopeTest {

    @Test
    void allThreeValuesExist() {
        ConfigScope[] values = ConfigScope.values();
        assertEquals(3, values.length);
    }

    @Test
    void globalValueExists() {
        assertNotNull(ConfigScope.GLOBAL);
    }

    @Test
    void regionalValueExists() {
        assertNotNull(ConfigScope.REGIONAL);
    }

    @Test
    void localValueExists() {
        assertNotNull(ConfigScope.LOCAL);
    }

    @Test
    void valueOfGlobal() {
        assertEquals(ConfigScope.GLOBAL, ConfigScope.valueOf("GLOBAL"));
    }

    @Test
    void valueOfRegional() {
        assertEquals(ConfigScope.REGIONAL, ConfigScope.valueOf("REGIONAL"));
    }

    @Test
    void valueOfLocal() {
        assertEquals(ConfigScope.LOCAL, ConfigScope.valueOf("LOCAL"));
    }

    @Test
    void valueOfInvalidThrows() {
        assertThrows(IllegalArgumentException.class, () -> ConfigScope.valueOf("INVALID"));
    }
}
