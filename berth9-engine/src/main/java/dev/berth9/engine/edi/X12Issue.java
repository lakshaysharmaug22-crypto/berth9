package dev.berth9.engine.edi;

/**
 * A structural problem found while parsing an interchange.
 *
 * @param level    INTERCHANGE, GROUP or TRANSACTION
 * @param code     for TRANSACTION issues, the AK5 syntax error code reported back in the 997
 *                 (1 = not supported, 2 = trailer missing, 3 = control number mismatch,
 *                 4 = segment count mismatch, 5 = segments in error)
 * @param message  human-readable description
 * @param position segment position the issue was detected at
 */
public record X12Issue(Level level, String code, String message, int position) {

    public enum Level {
        INTERCHANGE,
        GROUP,
        TRANSACTION
    }
}
