package dev.berth9.engine.transform;

import java.util.List;

/**
 * One named step in a field's transform pipeline, e.g. {@code amount} or {@code date(dd-MM-yyyy)}.
 * Implementations return null for null input unless their job is to replace nulls.
 */
@FunctionalInterface
public interface Transform {

    Object apply(Object value, List<String> args, TransformContext context);
}
