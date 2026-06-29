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
 * {@link ConfigPolicy} of Seam-1 {@link Role}/{@link Policy}/{@link PolicyRule} types plus principal→role
 * bindings (O-6 Seam 2a).
 *
 * <h2>Why a text format</h2>
 * There is no JSON library in {@code src/main} and the codebase idiom is hand-rolled codecs. Authorization
 * policy is operator-authored and operator-inspected, so a small line-oriented <b>text</b> format is more
 * operable than binary (trivially diffable and hand-writable) and avoids adding a JSON parser — a parsing /
 * attack surface this security path would otherwise have to clear. No new dependency.
 *
 * <h2>Key layout (flat-key prefix {@code _acl/})</h2>
 * <ul>
 *   <li>{@code _acl/roles/<roleName>}     — value lists the role's rules, one per line.</li>
 *   <li>{@code _acl/bindings/<principal>} — value lists the principal's role names, one per line.</li>
 * </ul>
 * The {@code <roleName>} / {@code <principal>} is the verbatim key suffix.
 *
 * <h2>Value grammar</h2>
 * UTF-8 text split on {@code '\n'}; a single trailing {@code '\r'} per line is stripped; a {@link
 * String#isBlank() blank} line or a line whose first non-whitespace character is {@code '#'} is ignored.
 * <ul>
 *   <li><b>Role line:</b> {@code <effect> <caps> <prefix>} — {@code effect} ∈ {{@code allow},{@code deny}}
 *       (lowercase, exact); {@code caps} = comma-separated {@link AclService.Permission} names (exact, no
 *       spaces, non-empty); {@code prefix} = the VERBATIM remainder after the space following {@code caps}
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
 * validation pass), of the store, or of metrics — so it is unit-testable in isolation. It performs literal
 * {@code key.startsWith(prefix)} matching only; glob / segment-aware matching is DL-O3-02-deferred.
 */
public final class PolicySerializer {

    /** Reserved flat-key prefix under which config-sourced policy lives. */
    public static final String ACL_PREFIX = "_acl/";
    private static final String ROLES_PREFIX = ACL_PREFIX + "roles/";
    private static final String BINDINGS_PREFIX = ACL_PREFIX + "bindings/";

    private PolicySerializer() {
    }

    /**
     * Parses an {@code _acl/} subtree (full flat keys → raw value bytes) into a {@link ConfigPolicy}.
     *
     * @param aclSubtree the {@code _acl/}-prefixed key→value entries (non-null; values non-null)
     * @return the parsed config-policy
     * @throws PolicyParseException if any entry is structurally malformed (fail-closed — reject whole load)
     */
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

            if (key.startsWith(ROLES_PREFIX)) {
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
                capsToken = rest;   // no prefix field ⇒ empty prefix (matches every key)
                prefix = "";
            } else {
                capsToken = rest.substring(0, sp2);
                prefix = rest.substring(sp2 + 1);  // VERBATIM remainder (may be empty / may contain spaces)
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
            try {
                caps.add(AclService.Permission.valueOf(capName));
            } catch (IllegalArgumentException e) {
                throw new PolicyParseException(
                        "unknown capability '" + capName + "' in role '" + roleName + "' rule '" + line
                                + "' (expected one of READ,LIST,WRITE,WATCH,ADMIN)");
            }
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
            // A binding line is a single verbatim role name. A non-blank line that is whitespace-only is
            // caught by isIgnorable (blank); anything else is taken verbatim as the role name.
            roleNames.add(line);
        }
        return roleNames;
    }

    /** Splits on '\n' (the trailing '\r' of a CRLF line is stripped per-line by {@link #stripTrailingCr}). */
    private static String[] splitLines(String text) {
        return text.split("\n", -1);
    }

    private static String stripTrailingCr(String line) {
        return (!line.isEmpty() && line.charAt(line.length() - 1) == '\r')
                ? line.substring(0, line.length() - 1)
                : line;
    }

    /** A line is ignorable if it is blank or its first non-whitespace character is '#'. */
    private static boolean isIgnorable(String line) {
        return line.isBlank() || line.stripLeading().startsWith("#");
    }
}
