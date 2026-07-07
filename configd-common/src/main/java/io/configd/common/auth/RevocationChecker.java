package io.configd.common.auth;

import java.security.cert.X509Certificate;
import java.util.List;

/**
 * The pluggable online-revocation lookup seam for a CLIENT / edge certificate - the mechanism behind the
 * {@link RevocationMode} posture. An implementation performs the actual responder call (an OCSP query per
 * RFC 6960, a CRL-file lookup, or a stapled-response cache) and reports a three-valued
 * {@link RevocationStatus}; the lax-vs-strict fail-open/fail-closed decision is the caller's
 * ({@code RevocationPolicy}), not the checker's, so a checker only has to answer "revoked / not-revoked /
 * couldn't-tell".
 *
 * <p><b>Fail-safe contract.</b> An implementation MUST NOT throw for a routine unreachable / timeout
 * condition - it returns {@link RevocationStatus#UNKNOWN} so the mode decides the posture. It also MUST
 * be bounded (its own responder timeout) and side-effect-free with respect to the connection: it is
 * consulted at connection admission, off the Raft apply / replay / encryption path.
 *
 * <p><b>Interior exemption.</b> A checker is only ever wired to the EDGE / client cert plane. The Raft
 * inter-node plane and the break-glass admin credential never consult one (by construction), so a down
 * responder under {@code strict} can never brick consensus - see {@link RevocationPolicy#exemptInterNode}.
 */
@FunctionalInterface
public interface RevocationChecker {

    /**
     * Reports the revocation status of {@code leaf} (the end-entity certificate), given its full verified
     * {@code chain} for issuer resolution. Never throws for a routine responder-unreachable condition -
     * returns {@link RevocationStatus#UNKNOWN} instead.
     *
     * @param leaf  the peer's own (leaf) certificate - the subject of the revocation query
     * @param chain the full verified chain (leaf-first), for issuer / responder-URL resolution
     * @return the three-valued revocation status
     */
    RevocationStatus check(X509Certificate leaf, List<X509Certificate> chain);
}
