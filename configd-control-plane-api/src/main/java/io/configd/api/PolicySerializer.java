package io.configd.api;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Strict, fail-closed codec between the {@code _acl/} config subtree (opaque {@code byte[]} values) and a
 * {@link ConfigPolicy} of {@link Role}/{@link Policy}/{@link PolicyRule} types plus principal-to-role
 * bindings.
 *
 * <h2>Why a text format</h2>
 * There is no JSON library in {@code src/main} and the codebase idiom is hand-rolled codecs. Authorization
 * policy is operator-authored and operator-inspected, so a small line-oriented <b>text</b> format is more
 * operable than binary (trivially diffable and hand-writable) and avoids adding a JSON parser - a parsing /
 * attack surface this security path would otherwise have to clear. No new dependency.
 *
 * <h2>Key layout (flat-key prefix {@code _acl/})</h2>
 * <ul>
 *   <li>{@code _acl/roles/<roleName>}     - value lists the role's rules, one per line.</li>
 *   <li>{@code _acl/bindings/<principal>} - value lists the principal's role names, one per line.</li>
 *   <li>{@code _acl/format}               - reserved metadata: the ACL grammar version (see below).</li>
 * </ul>
 * The {@code <roleName>} / {@code <principal>} is the verbatim key suffix.
 *
 * <h2>Format version (the frozen-grammar interlock)</h2>
 * The reserved {@code _acl/format} key names the grammar version; its value must be the integer {@link
 * #SUPPORTED_ACL_FORMAT}. Its <b>absence means version {@code 1}</b> - so every deployment that predates this
 * key parses byte-identically. A present value that is not the supported version fails closed (rejects the
 * WHOLE load) exactly as any binary codec rejects an unknown version byte, rather than silently misparsing
 * newer bytes under the old grammar. This matters because a role line's {@code prefix} and a binding line are
 * taken VERBATIM (below), so a future grammar that appended a positional field to an existing line would
 * otherwise be silently absorbed by an old reader - and since {@code _acl/} is cluster-replicated and parsed
 * on every node, that silent divergence would be an authorization split-brain in a mixed-version window.
 * <p>
 * <b>Compatibility rule (format {@code 1} is FROZEN):</b> a future grammar change MUST bump {@code _acl/format}
 * (an old node then fails closed on the whole subtree and keeps its last-good policy) and MUST NOT extend an
 * existing role/binding line's positional grammar without that bump. New capability MAY instead ride a new
 * {@code _acl/<shape>/…} key or a new effect/capability keyword, both of which an old reader already fail-
 * closes on (an unrecognized key shape / effect / capability - below).
 *
 * <h2>Value grammar</h2>
 * UTF-8 text split on {@code '\n'}; a single trailing {@code '\r'} per line is stripped; a {@link
 * String#isBlank() blank} line or a line whose first non-whitespace character is {@code '#'} is ignored.
 * <ul>
 *   <li><b>Role line:</b> {@code <effect> <caps> <prefix>} - {@code effect} in {{@code allow},{@code deny}}
 *       (lowercase, exact); {@code caps} = comma-separated grantable {@link AclService.Permission} names
 *       (exact, no spaces, non-empty) - the four grantable capabilities {@code READ}/{@code WRITE}/{@code
 *       WATCH}/{@code ADMIN}; the reserved {@code LIST} value is rejected on either effect (see below);
 *       {@code prefix} = the VERBATIM remainder after the space following {@code caps}
 *       (so a literal flat-key prefix may contain spaces; it may also be empty, which matches every key).
 *       Each line becomes one {@link PolicyRule} (allow-set xor deny-set populated). The role is
 *       {@code Role(roleName, [Policy(roleName, <its rules>)])}.</li>
 *   <li><b>Binding line:</b> a single role name (verbatim).</li>
 * </ul>
 *
 * <h2>Fail-closed semantics</h2>
 * Any structurally malformed input throws {@link PolicyParseException} so the whole load is REJECTED and the
 * loader keeps the last-good policy (never deny-all, never allow-all). Rejected cases: an {@code _acl/} key
 * not matching the {@code roles/}/{@code bindings/} layout (or with an empty suffix); an unknown {@code
 * effect}; an empty or unknown capability; a role line missing the {@code effect}/{@code caps} fields; a
 * blank binding role name on a non-blank line.
 * <p>
 * <b>A well-formed-but-incomplete policy is NOT a failure.</b> A binding that names a role with no
 * {@code _acl/roles/<name>} key parses successfully and is simply inert (that role contributes nothing,
 * default-deny) until the role key appears. This is what lets the loader's idempotent whole-subtree rebuild
 * converge across multi-key writes that arrive in any order (and across the snapshot / WAL-suffix split).
 * <p>
 * This codec is PURE: it has no knowledge of reserved names (the loader applies reservation as a separate
 * validation pass), of the store, or of metrics - so it is unit-testable in isolation. It performs literal
 * {@code key.startsWith(prefix)} matching only; glob / segment-aware matching is deferred.
 * <p>
 * <b>Reserved {@code LIST}.</b> {@code LIST} is a reserved, non-grantable capability: a role line whose
 * {@code caps} contain {@code LIST} - on {@code allow} or {@code deny} - is rejected with {@link
 * PolicyParseException}, because there is deliberately no list/enumerate operation to gate. The value is
 * retained in {@link AclService.Permission} only for frozen-ordinal stability. See {@link AclService.Permission}.
 */
public final class PolicySerializer {

    /** Reserved flat-key prefix under which config-sourced policy lives. */
    public static final String ACL_PREFIX = "_acl/";
    private static final String ROLES_PREFIX = ACL_PREFIX + "roles/";
    private static final String BINDINGS_PREFIX = ACL_PREFIX + "bindings/";

    /**
     * Reserved metadata key naming the ACL grammar version. Absent ⇒ version {@link #SUPPORTED_ACL_FORMAT}
     * (byte-identical to every deployment that predates this key). A present value that is not the supported
     * version fails closed. This is a KEY, not a byte prefix, so it never touches the frozen serialized form
     * of an existing {@code _acl/roles/…}/{@code _acl/bindings/…} value.
     */
    private static final String FORMAT_KEY = ACL_PREFIX + "format";

    /**
     * The only ACL grammar version this build parses. Format {@code 1} is FROZEN: a future grammar change
     * MUST bump this sentinel (an old node then whole-subtree-rejects the newer policy and keeps last-good)
     * and MUST NOT extend an existing role/binding line's positional grammar without that bump. See the
     * class javadoc's "Format version" section for the full compatibility rule.
     */
    public static final int SUPPORTED_ACL_FORMAT = 1;

    private PolicySerializer() {
    }

    public static ConfigPolicy parse(Map<String, byte[]> aclSubtree) {
        Objects.requireNonNull(aclSubtree, "aclSubtree must not be null");

        Map<String, Role> roles = new LinkedHashMap<>();
        Map<String, Set<String>> bindings = new LinkedHashMap<>();

        for (Map.Entry<String, byte[]> entry : aclSubtree.entrySet()) {
            String key = entry.getKey();
            byte[] value = entry.getValue();
            if (key == null || value == null) {
                throw new PolicyParseException("null key or value in _acl/ subtree");
            }
            String text = new String(value, StandardCharsets.UTF_8);

            if (key.equals(FORMAT_KEY)) {
                parseFormatVersion(text);
            } else if (key.startsWith(ROLES_PREFIX)) {
                String roleName = key.substring(ROLES_PREFIX.length());
                if (roleName.isEmpty()) {
                    throw new PolicyParseException("empty role name in key '" + key + "'");
                }
                List<PolicyRule> rules = parseRoleRules(roleName, text);
                roles.put(roleName, new Role(roleName, List.of(new Policy(roleName, rules))));
            } else if (key.startsWith(BINDINGS_PREFIX)) {
                String principal = key.substring(BINDINGS_PREFIX.length());
                if (principal.isEmpty()) {
                    throw new PolicyParseException("empty principal in key '" + key + "'");
                }
                bindings.put(principal, parseBinding(principal, text));
            } else {
                throw new PolicyParseException(
                        "unrecognized _acl/ key shape '" + key + "' (expected " + ROLES_PREFIX
                                + "<role> or " + BINDINGS_PREFIX + "<principal>)");
            }
        }
        return new ConfigPolicy(roles, bindings);
    }

    private static void parseFormatVersion(String text) {
        String token = text.strip();
        int format;
        try {
            format = Integer.parseInt(token);
        } catch (NumberFormatException e) {
            throw new PolicyParseException("malformed " + FORMAT_KEY + " value '" + text
                    + "' (expected the integer ACL grammar version " + SUPPORTED_ACL_FORMAT + ")");
        }
        if (format != SUPPORTED_ACL_FORMAT) {
            throw new PolicyParseException("unsupported ACL policy format " + format + " (this build parses "
                    + SUPPORTED_ACL_FORMAT + ") - failing closed; a newer node wrote a newer policy grammar");
        }
    }

    private static List<PolicyRule> parseRoleRules(String roleName, String text) {
        List<PolicyRule> rules = new ArrayList<>();
        for (String raw : splitLines(text)) {
            String line = stripTrailingCr(raw);
            if (isIgnorable(line)) {
                continue;
            }
            int sp1 = line.indexOf(' ');
            if (sp1 < 0) {
                throw new PolicyParseException(
                        "malformed rule in role '" + roleName + "': '" + line
                                + "' (expected '<allow|deny> <CAP,...> <prefix>')");
            }
            String effect = line.substring(0, sp1);
            boolean allowEffect;
            if (effect.equals("allow")) {
                allowEffect = true;
            } else if (effect.equals("deny")) {
                allowEffect = false;
            } else {
                throw new PolicyParseException(
                        "unknown effect '" + effect + "' in role '" + roleName + "' (expected allow|deny)");
            }

            String rest = line.substring(sp1 + 1);
            int sp2 = rest.indexOf(' ');
            String capsToken;
            String prefix;
            if (sp2 < 0) {
                capsToken = rest;
                prefix = "";
            } else {
                capsToken = rest.substring(0, sp2);
                prefix = rest.substring(sp2 + 1);
            }

            Set<AclService.Permission> caps = parseCaps(roleName, line, capsToken);
            rules.add(allowEffect
                    ? new PolicyRule(prefix, caps, Set.of())
                    : new PolicyRule(prefix, Set.of(), caps));
        }
        return rules;
    }

    private static Set<AclService.Permission> parseCaps(String roleName, String line, String capsToken) {
        if (capsToken.isEmpty()) {
            throw new PolicyParseException(
                    "empty capability list in role '" + roleName + "' rule '" + line + "'");
        }
        EnumSet<AclService.Permission> caps = EnumSet.noneOf(AclService.Permission.class);
        for (String capName : capsToken.split(",", -1)) {
            if (capName.isEmpty()) {
                throw new PolicyParseException(
                        "empty capability token in role '" + roleName + "' rule '" + line
                                + "' (no blank/trailing comma)");
            }
            AclService.Permission cap;
            try {
                cap = AclService.Permission.valueOf(capName);
            } catch (IllegalArgumentException e) {
                throw new PolicyParseException(
                        "unknown capability '" + capName + "' in role '" + roleName + "' rule '" + line
                                + "' (expected one of READ,WRITE,WATCH,ADMIN)");
            }
            if (cap == AclService.Permission.LIST) {
                throw new PolicyParseException(
                        "capability 'LIST' is reserved and not grantable (no list/enumerate operation exists"
                                + " to gate) in role '" + roleName + "' rule '" + line + "'");
            }
            caps.add(cap);
        }
        return caps;
    }

    private static Set<String> parseBinding(String principal, String text) {
        Set<String> roleNames = new LinkedHashSet<>();
        for (String raw : splitLines(text)) {
            String line = stripTrailingCr(raw);
            if (isIgnorable(line)) {
                continue;
            }
            roleNames.add(line);
        }
        return roleNames;
    }

    private static String[] splitLines(String text) {
        return text.split("\n", -1);
    }

    private static String stripTrailingCr(String line) {
        return (!line.isEmpty() && line.charAt(line.length() - 1) == '\r')
                ? line.substring(0, line.length() - 1)
                : line;
    }

    private static boolean isIgnorable(String line) {
        return line.isBlank() || line.stripLeading().startsWith("#");
    }
}
