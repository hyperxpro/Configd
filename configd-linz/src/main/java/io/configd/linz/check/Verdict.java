package io.configd.linz.check;

/**
 * The verdict of the trusted Porcupine checker over a recorded history.
 *
 * <p>{@code INDETERMINATE} (a checker timeout, or a checker error) is <b>never</b>
 * treated as a pass - a run that cannot be decided is logged as indeterminate,
 * not green.
 */
public enum Verdict {
    LINEARIZABLE,
    NON_LINEARIZABLE,
    INDETERMINATE
}
