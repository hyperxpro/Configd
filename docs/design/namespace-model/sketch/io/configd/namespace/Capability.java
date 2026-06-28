package io.configd.namespace;

/**
 * The v1 capability set (access-control.md §2, RFC A5-1). {@code DENY} is an <em>effect</em> on a rule
 * (see {@link PolicyRule.Effect}), not a capability, so it is not listed here.
 *
 * <ul>
 *   <li>{@link #READ}  — read a value at a concrete path.</li>
 *   <li>{@link #LIST}  — enumerate children/descendants of a path. DISTINCT from READ (R-CAP-1):
 *       knowing a key exists is sensitive.</li>
 *   <li>{@link #WRITE} — put/delete at a concrete path (coarse in v1; create/update/delete split = v2).</li>
 *   <li>{@link #WATCH} — subscribe to a change stream. SEPARATE from READ but REQUIRES READ (R-CAP-2):
 *       a watch must never expose what a read could not.</li>
 *   <li>{@link #ADMIN} — manage policies/roles for a subtree; access reserved {@code /_acl/},
 *       {@code /_system/}.</li>
 * </ul>
 */
public enum Capability { READ, LIST, WRITE, WATCH, ADMIN }
