package io.configd.authn.oidc;

import com.nimbusds.jwt.JWTClaimsSet;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Claim-path resolution and value coercion for the {@link ClaimsRoleMapper} (per-IdP claim shapes). */
final class ClaimsRoleMapperTest {

    @Test
    void absentClaimPathDefaultDenies() {
        ClaimsRoleMapper mapper = new ClaimsRoleMapper(null, Map.of(), "");
        assertTrue(mapper.rolesOf(new JWTClaimsSet.Builder().claim("roles", List.of("x")).build()).isEmpty());
    }

    @Test
    void topLevelArrayPassesThrough() {
        ClaimsRoleMapper mapper = new ClaimsRoleMapper("groups", Map.of(), "");
        JWTClaimsSet claims = new JWTClaimsSet.Builder().claim("groups", List.of("g1", "g2")).build();
        assertEquals(Set.of("g1", "g2"), mapper.rolesOf(claims));
    }

    @Test
    void nestedPathIsWalked() {
        ClaimsRoleMapper mapper = new ClaimsRoleMapper("realm_access.roles", Map.of(), "");
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .claim("realm_access", Map.of("roles", List.of("admin", "reader"))).build();
        assertEquals(Set.of("admin", "reader"), mapper.rolesOf(claims));
    }

    @Test
    void namespacedUriClaimNameIsOneWholeKey() {
        // A URI claim name contains dots; whole-key-first resolution must not split it on the dots.
        String claimName = "https://configd.example/roles";
        ClaimsRoleMapper mapper = new ClaimsRoleMapper(claimName, Map.of(), "");
        JWTClaimsSet claims = new JWTClaimsSet.Builder().claim(claimName, List.of("ops")).build();
        assertEquals(Set.of("ops"), mapper.rolesOf(claims));
    }

    @Test
    void spaceDelimitedStringIsSplit() {
        ClaimsRoleMapper mapper = new ClaimsRoleMapper("scope", Map.of(), "");
        JWTClaimsSet claims = new JWTClaimsSet.Builder().claim("scope", "read  write   admin").build();
        assertEquals(Set.of("read", "write", "admin"), mapper.rolesOf(claims));
    }

    @Test
    void nonEmptyMapIsAnAllowlist() {
        ClaimsRoleMapper mapper = new ClaimsRoleMapper("roles",
                Map.of("kc-admin", "admin", "kc-reader", "reader"), "");
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .claim("roles", List.of("kc-admin", "kc-nobody")).build();
        assertEquals(Set.of("admin"), mapper.rolesOf(claims), "unmapped external values are dropped");
    }

    @Test
    void passthroughAppliesPrefix() {
        ClaimsRoleMapper mapper = new ClaimsRoleMapper("roles", Map.of(), "idp/");
        JWTClaimsSet claims = new JWTClaimsSet.Builder().claim("roles", List.of("a", "b")).build();
        assertEquals(Set.of("idp/a", "idp/b"), mapper.rolesOf(claims));
    }

    @Test
    void missingNestedSegmentDefaultDenies() {
        ClaimsRoleMapper mapper = new ClaimsRoleMapper("resource_access.configd.roles", Map.of(), "");
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .claim("resource_access", Map.of("other", Map.of("roles", List.of("x")))).build();
        assertTrue(mapper.rolesOf(claims).isEmpty());
    }
}
