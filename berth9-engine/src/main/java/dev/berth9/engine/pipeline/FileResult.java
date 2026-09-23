package dev.berth9.engine.pipeline;

import dev.berth9.engine.model.Violation;

import java.util.List;

/**
 * Totals for one processed file.
 *
 * @param fileIssues file-level findings, e.g. a control total that disagrees with what was read
 */
public record FileResult(long total, long valid, long warnings, long errors, List<Violation> fileIssues, long elapsedMillis) {

    public FileResult {
        fileIssues = List.copyOf(fileIssues);
    }

    public double errorRate() {
        return total == 0 ? 0 : errors / (double) total;
    }
}
