package io.configd.namespace;

/**
 * A path PATTERN — the target of an ACL rule, a {@code list}, or a watch (RFC §3.4). A stored
 * {@link ConfigPath} is always concrete; a pattern may denote a subtree, a single segment level, or an
 * exact path. Patterns are LOGICAL (they classify the hierarchy); they never route (INV-PATH).
 *
 * <ul>
 *   <li>{@link Subtree}      — {@code /a/} or {@code /a/**}: the whole subtree under {@code base}
 *       (recursive). {@code base == ""} is the root subtree {@code /**} (matches everything).</li>
 *   <li>{@link SingleSegment}— {@code /a/*}: exactly one segment under {@code base} (direct children).</li>
 *   <li>{@link Exact}        — {@code /a/b}: the exact path.</li>
 * </ul>
 *
 * Provides {@link #matches} (does a concrete path fall under this pattern — A5-4 per-key eval),
 * {@link #contains} (does this pattern fully cover a target pattern — watch/list "covers all of T",
 * §6.1), and {@link #intersects} (does this pattern overlap a target — deny-overlap, §6).
 */
public sealed interface PathPattern permits PathPattern.Exact, PathPattern.Subtree, PathPattern.SingleSegment {

    boolean matches(ConfigPath path);

    boolean contains(PathPattern target);

    boolean intersects(PathPattern target);

    /** The canonical base path of this pattern ({@code ""} for the root subtree). */
    String base();

    /**
     * Parses a pattern string (RFC §3.4): trailing {@code /**} or {@code /} ⇒ subtree; trailing
     * {@code /*} ⇒ single-segment; {@code /} or {@code /**} ⇒ root subtree; otherwise exact.
     */
    static PathPattern parse(String raw) {
        if (raw == null || raw.isEmpty()) {
            throw new IllegalArgumentException("pattern must be non-empty");
        }
        if (raw.equals("/") || raw.equals("/**")) {
            return new Subtree(""); // root subtree — matches everything
        }
        if (raw.endsWith("/**")) {
            return new Subtree(ConfigPath.normalize(raw.substring(0, raw.length() - 3)));
        }
        if (raw.equals("/*")) {
            return new SingleSegment(""); // direct children of root
        }
        if (raw.endsWith("/*")) {
            return new SingleSegment(ConfigPath.normalize(raw.substring(0, raw.length() - 2)));
        }
        if (raw.endsWith("/")) {
            return new Subtree(ConfigPath.normalize(raw)); // "/a/" -> normalize -> "/a"
        }
        return new Exact(ConfigPath.normalize(raw));
    }

    /** True iff concrete path {@code p} is {@code base} itself or strictly below it. */
    private static boolean underBase(String base, String p) {
        if (base.isEmpty()) {
            return true; // root subtree contains every absolute path
        }
        return p.equals(base) || p.startsWith(base + "/");
    }

    /** Conservative subtree-overlap on two bases: either contains the other (or equal/root). */
    private static boolean basesOverlap(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) {
            return true;
        }
        return a.equals(b) || a.startsWith(b + "/") || b.startsWith(a + "/");
    }

    /** {@code /a/b} — the exact path. */
    record Exact(String base) implements PathPattern {
        @Override public boolean matches(ConfigPath path) {
            return path.value().equals(base);
        }
        @Override public boolean contains(PathPattern target) {
            // An exact pattern covers only the identical exact target.
            return target instanceof Exact e && e.base.equals(base);
        }
        @Override public boolean intersects(PathPattern target) {
            return switch (target) {
                case Exact e -> e.base.equals(base);
                case Subtree s -> underBase(s.base, base);
                case SingleSegment ss -> underBase(ss.base, base)
                        && base.length() > ss.base.length()
                        && base.indexOf('/', ss.base.length() + 1) == -1;
            };
        }
    }

    /** {@code /a/} or {@code /a/**} — the recursive subtree under {@code base} ({@code ""} = root). */
    record Subtree(String base) implements PathPattern {
        @Override public boolean matches(ConfigPath path) {
            return underBase(base, path.value());
        }
        @Override public boolean contains(PathPattern target) {
            // This subtree covers the target iff the target's base lies at/under this base.
            return underBase(base, target.base());
        }
        @Override public boolean intersects(PathPattern target) {
            return basesOverlap(base, target.base());
        }
    }

    /** {@code /a/*} — exactly one segment under {@code base} ({@code ""} = direct children of root). */
    record SingleSegment(String base) implements PathPattern {
        @Override public boolean matches(ConfigPath path) {
            String p = path.value();
            if (!underBase(base, p) || p.equals(base)) {
                return false;
            }
            // direct child: no further '/' after the base + '/'
            int childStart = base.length() + 1; // skip the delimiter
            return p.indexOf('/', childStart) == -1;
        }
        @Override public boolean contains(PathPattern target) {
            // Covers an exact direct child, or itself.
            return switch (target) {
                case Exact e -> matches(new ConfigPath(e.base));
                case SingleSegment ss -> ss.base.equals(base);
                case Subtree s -> false; // a single level cannot cover a recursive subtree
            };
        }
        @Override public boolean intersects(PathPattern target) {
            // Conservative: treat the single level as its base subtree for overlap (over-denies safely).
            return basesOverlap(base, target.base());
        }
    }
}
