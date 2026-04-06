package io.configd.common;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a code location as a fault injection point.
 * In simulation mode, the annotated condition may be forced true/false
 * to inject faults (extra latency, wrong error codes, partial writes, etc.).
 * 
 * Inspired by FoundationDB's BUGGIFY macro.
 * In production, these annotations have zero runtime cost.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.LOCAL_VARIABLE})
public @interface Buggify {
    /** Probability of activation when in simulation mode (0.0 to 1.0). Default: 0.25 */
    double probability() default 0.25;
    
    /** Human-readable description of what this injection point does. */
    String value() default "";
}
