package io.configd.edge;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class StrongReadKeyClassTest {

    @Test
    void defaultPrefixIsSecure() {
        assertEquals("secure/", StrongReadKeyClass.DEFAULT_PREFIX);
    }

    @Test
    void defaultClassMatchesSecurePrefixOnly() {
        StrongReadKeyClass k = StrongReadKeyClass.DEFAULT;
        assertTrue(k.isStrongReadKey("secure/killswitch"));
        assertTrue(k.isStrongReadKey("secure/"));
        assertFalse(k.isStrongReadKey("app/config"));
        assertFalse(k.isStrongReadKey("secur/almost")); // not the full prefix
        assertEquals(Set.of("secure/"), k.prefixes());
    }

    @Test
    void nullKeyIsNotStrongRead() {
        assertFalse(StrongReadKeyClass.DEFAULT.isStrongReadKey(null));
    }

    @Test
    void multiplePrefixesAllMatch() {
        StrongReadKeyClass k = new StrongReadKeyClass(Set.of("secure/", "acl/"));
        assertTrue(k.isStrongReadKey("secure/x"));
        assertTrue(k.isStrongReadKey("acl/y"));
        assertFalse(k.isStrongReadKey("public/z"));
        assertEquals(Set.of("secure/", "acl/"), k.prefixes());
    }

    @Test
    void emptyPrefixSetMatchesNothing() {
        StrongReadKeyClass k = new StrongReadKeyClass(Set.of());
        assertFalse(k.isStrongReadKey("secure/anything"));
        assertTrue(k.prefixes().isEmpty());
    }

    @Test
    void blankPrefixIsRejected() {
        Set<String> withBlank = new LinkedHashSet<>();
        withBlank.add("  ");
        assertThrows(IllegalArgumentException.class, () -> new StrongReadKeyClass(withBlank));
    }

    @Test
    void nullPrefixElementIsRejected() {
        Set<String> withNull = new LinkedHashSet<>();
        withNull.add(null);
        assertThrows(NullPointerException.class, () -> new StrongReadKeyClass(withNull));
    }

    @Test
    void nullPrefixSetIsRejected() {
        assertThrows(NullPointerException.class, () -> new StrongReadKeyClass(null));
    }

    @Test
    void prefixesIsUnmodifiable() {
        StrongReadKeyClass k = StrongReadKeyClass.DEFAULT;
        assertThrows(UnsupportedOperationException.class, () -> k.prefixes().add("hack/"));
    }

    @Test
    void toStringNamesTheClassAndPrefixes() {
        String s = new StrongReadKeyClass(Set.of("secure/")).toString();
        assertTrue(s.contains("StrongReadKeyClass"), "toString must name the class: " + s);
        assertTrue(s.contains("secure/"), "toString must include the prefixes: " + s);
    }
}
