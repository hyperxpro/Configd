package io.configd.authn.oidc;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.JWKSetCacheRefreshEvaluator;
import com.nimbusds.jose.jwk.source.JWKSetSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;

import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test-only helpers for the OIDC authenticator: it mints RS256/ES256 (and adversarial {@code alg:none} /
 * RS-HS-confusion) access tokens against generated keys and exposes an in-memory JWKS the authenticator can
 * resolve without a socket. A {@link CountingJWKSetSource} counts and controls JWKS retrievals so rotation
 * and rate-limit behaviour can be asserted deterministically.
 */
final class OidcTestSupport {

    static final String ISSUER = "https://idp.example/realms/configd";
    static final String AUDIENCE = "configd-api";
    static final JOSEObjectType AT_JWT = new JOSEObjectType("at+jwt");

    private OidcTestSupport() {
    }

    static RSAKey rsaKey(String kid) throws Exception {
        return new RSAKeyGenerator(2048).keyID(kid).generate();
    }

    static ECKey ecKey(String kid) throws Exception {
        return new ECKeyGenerator(Curve.P_256).keyID(kid).generate();
    }

    static JWKSet publicJwks(JWK... keys) {
        return new JWKSet(List.of(keys)).toPublicJWKSet();
    }

    /** A standard, currently-valid claims set for {@link #ISSUER}/{@link #AUDIENCE}. */
    static JWTClaimsSet.Builder claims(String subject) {
        long now = System.currentTimeMillis();
        return new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(subject)
                .audience(AUDIENCE)
                .issueTime(new Date(now))
                .notBeforeTime(new Date(now - 5_000))
                .expirationTime(new Date(now + 3_600_000));
    }

    static String signRs256(RSAKey key, JWTClaimsSet claims) throws Exception {
        return sign(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).type(AT_JWT).build(),
                claims, new RSASSASigner(key));
    }

    static String signRs256(RSAKey key, JWTClaimsSet claims, JOSEObjectType type) throws Exception {
        JWSHeader.Builder header = new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID());
        if (type != null) {
            header.type(type);
        }
        return sign(header.build(), claims, new RSASSASigner(key));
    }

    static String signEs256(ECKey key, JWTClaimsSet claims) throws Exception {
        return sign(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(key.getKeyID()).type(AT_JWT).build(),
                claims, new ECDSASigner(key));
    }

    /** An {@code alg:none} (plaintext) JWT - the attack RFC 9068 §4 forbids. */
    static String algNone(JWTClaimsSet claims) {
        return new PlainJWT(claims).serialize();
    }

    /**
     * The algorithm-confusion attack (RFC 8725 §2.1): an HS256 token signed using the RSA public key's
     * encoded bytes as the HMAC secret, carrying the legitimate key's {@code kid}. A vulnerable server that
     * fed the JWKS RSA public key to an HMAC verifier would accept it.
     */
    static String hs256WithRsaPublicKey(RSAKey key, JWTClaimsSet claims) throws Exception {
        byte[] secret = key.toPublicKey().getEncoded(); // X.509 SPKI DER, > 32 bytes
        return sign(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(key.getKeyID()).type(AT_JWT).build(),
                claims, new MACSigner(secret));
    }

    private static String sign(JWSHeader header, JWTClaimsSet claims,
                               com.nimbusds.jose.JWSSigner signer) throws Exception {
        SignedJWT jwt = new SignedJWT(header, claims);
        jwt.sign(signer);
        return jwt.serialize();
    }

    /** A mutable, retrieval-counting {@link JWKSetSource} for rotation and rate-limit tests. */
    static final class CountingJWKSetSource implements JWKSetSource<SecurityContext> {
        private volatile JWKSet current;
        private final AtomicInteger fetches = new AtomicInteger();

        CountingJWKSetSource(JWKSet initial) {
            this.current = initial;
        }

        void setJwks(JWKSet next) {
            this.current = next;
        }

        int fetchCount() {
            return fetches.get();
        }

        @Override
        public JWKSet getJWKSet(JWKSetCacheRefreshEvaluator refreshEvaluator, long currentTime,
                                SecurityContext context) {
            fetches.incrementAndGet();
            return current;
        }

        @Override
        public void close() {
        }
    }
}
