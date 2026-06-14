package io.configd.common;

import org.junit.jupiter.api.Test;

import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * RFC 5869 conformance for {@link Hkdf} (HKDF-SHA256).
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc5869#appendix-A">RFC 5869 Appendix A</a>
 */
class HkdfTest {

    private static final HexFormat HEX = HexFormat.of();

    /** RFC 5869 Appendix A.1 — Test Case 1 (basic, SHA-256). */
    @Test
    void rfc5869TestCase1() {
        byte[] ikm = HEX.parseHex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b");
        byte[] salt = HEX.parseHex("000102030405060708090a0b0c");
        byte[] info = HEX.parseHex("f0f1f2f3f4f5f6f7f8f9");
        int l = 42;

        byte[] expectedPrk = HEX.parseHex(
                "077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5");
        byte[] expectedOkm = HEX.parseHex(
                "3cb25f25faacd57a90434f64d0362f2a"
                        + "2d2d0a90cf1a5a4c5db02d56ecc4c5bf"
                        + "34007208d5b887185865");

        byte[] prk = Hkdf.extract(salt, ikm);
        assertArrayEquals(expectedPrk, prk, "HKDF-Extract PRK must match RFC 5869 TC1");

        byte[] okm = Hkdf.expand(prk, info, l);
        assertArrayEquals(expectedOkm, okm, "HKDF-Expand OKM must match RFC 5869 TC1");

        // deriveKey (extract-then-expand) must produce the same OKM.
        assertArrayEquals(expectedOkm, Hkdf.deriveKey(ikm, salt, info, l),
                "deriveKey must equal expand(extract(...))");
    }

    /** RFC 5869 Appendix A.3 — Test Case 3 (zero-length salt and info, SHA-256). */
    @Test
    void rfc5869TestCase3_emptySaltAndInfo() {
        byte[] ikm = HEX.parseHex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b");
        int l = 42;

        byte[] expectedPrk = HEX.parseHex(
                "19ef24a32c717b167f33a91d6f648bdf96596776afdb6377ac434c1c293ccb04");
        byte[] expectedOkm = HEX.parseHex(
                "8da4e775a563c18f715f802a063c5a31"
                        + "b8a11f5c5ee1879ec3454e5f3c738d2d"
                        + "9d201395faa4b61a96c8");

        // null salt -> HashLen zero bytes; null info -> empty.
        assertArrayEquals(expectedPrk, Hkdf.extract(null, ikm));
        assertArrayEquals(expectedOkm, Hkdf.deriveKey(ikm, null, null, l));
    }

    @Test
    void expandRejectsOutOfRangeLength() {
        byte[] prk = Hkdf.extract(new byte[1], new byte[1]);
        assertThrows(IllegalArgumentException.class, () -> Hkdf.expand(prk, null, 0));
        assertThrows(IllegalArgumentException.class, () -> Hkdf.expand(prk, null, 255 * 32 + 1));
    }

    @Test
    void deriveKeyProducesRequestedLength() {
        byte[] k = Hkdf.deriveKey(new byte[]{1, 2, 3}, new byte[]{4}, "info".getBytes(), 32);
        assertEquals(32, k.length);
    }
}
