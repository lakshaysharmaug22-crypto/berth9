package dev.berth9.engine.transform;

import dev.berth9.engine.model.FieldType;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Converts a transformed value to the target field's declared type. */
public final class Coercion {

    private Coercion() {
    }

    public static Object coerce(Object value, FieldType type) {
        if (value == null) {
            return null;
        }
        return switch (type) {
            case STRING -> value instanceof BigDecimal b ? b.toPlainString() : value.toString();
            case DECIMAL -> tidy(TransformRegistry.decimal(value));
            case INTEGER -> toLong(value);
            case DATE -> value instanceof LocalDate d ? d : Dates.parseUnambiguous(value.toString());
            case BOOLEAN -> value instanceof Boolean b ? b : TransformRegistry.bool(value.toString());
        };
    }

    /** Drops trailing zeros beyond two decimals (2.1800 becomes 2.18) while keeping 0.125 and 1500 as sent. */
    static BigDecimal tidy(BigDecimal d) {
        if (d.scale() <= 2) {
            return d;
        }
        BigDecimal stripped = d.stripTrailingZeros();
        return stripped.scale() < 2 ? d.setScale(2, java.math.RoundingMode.UNNECESSARY) : stripped;
    }

    private static Long toLong(Object value) {
        if (value instanceof Long l) {
            return l;
        }
        BigDecimal d = TransformRegistry.decimal(value);
        try {
            return d.stripTrailingZeros().longValueExact();
        } catch (ArithmeticException e) {
            throw new TransformException("'" + d.toPlainString() + "' is not a whole number");
        }
    }
}
