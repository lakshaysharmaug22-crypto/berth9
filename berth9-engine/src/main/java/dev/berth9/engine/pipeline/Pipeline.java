package dev.berth9.engine.pipeline;

import dev.berth9.engine.map.Enricher;
import dev.berth9.engine.map.MappedRecord;
import dev.berth9.engine.map.RecordMapper;
import dev.berth9.engine.model.Severity;
import dev.berth9.engine.model.SourceRecord;
import dev.berth9.engine.model.Violation;
import dev.berth9.engine.read.RecordReader;
import dev.berth9.engine.rules.Rule;
import dev.berth9.engine.rules.RuleEngine;
import dev.berth9.engine.rules.ValidationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The per-file loop: read → map → enrich → validate, one record at a time, handing each outcome to a
 * sink (the server batches them into SQL). Nothing is buffered, so memory use does not grow with file size.
 */
public final class Pipeline {

    private final RecordMapper mapper;
    private final List<Rule> rules;
    private final Enricher enricher = new Enricher();
    private final RuleEngine ruleEngine;

    public Pipeline(RecordMapper mapper, List<Rule> rules, RuleEngine ruleEngine) {
        this.mapper = mapper;
        this.rules = List.copyOf(rules);
        this.ruleEngine = ruleEngine;
    }

    public FileResult run(RecordReader reader, ValidationContext context, Consumer<RecordOutcome> sink) {
        long started = System.nanoTime();
        long total = 0;
        long valid = 0;
        long warnings = 0;
        long errors = 0;
        while (reader.hasNext()) {
            RecordOutcome outcome = process(reader.next(), context);
            total++;
            switch (outcome.status()) {
                case VALID -> valid++;
                case WARNING -> warnings++;
                case ERROR -> errors++;
            }
            sink.accept(outcome);
        }
        List<Violation> fileIssues = controlChecks(reader.controlTotals(), total);
        long elapsed = (System.nanoTime() - started) / 1_000_000;
        return new FileResult(total, valid, warnings, errors, fileIssues, elapsed);
    }

    public RecordOutcome process(SourceRecord source, ValidationContext context) {
        MappedRecord mapped = mapper.map(source);
        MappedRecord enriched = enricher.apply(mapped, mapper.spec().enrichments(), context.reference());
        List<Violation> violations = ruleEngine.evaluate(enriched, rules, context);
        return new RecordOutcome(source, enriched, violations, statusOf(violations));
    }

    public static RecordStatus statusOf(List<Violation> violations) {
        boolean warning = false;
        for (Violation v : violations) {
            if (v.severity() == Severity.ERROR) {
                return RecordStatus.ERROR;
            }
            warning = true;
        }
        return warning ? RecordStatus.WARNING : RecordStatus.VALID;
    }

    static List<Violation> controlChecks(Map<String, String> declared, long actual) {
        List<Violation> issues = new ArrayList<>();
        String count = declared.get("lineCount");
        if (count != null) {
            try {
                long expected = Long.parseLong(count.strip());
                if (expected != actual) {
                    issues.add(Violation.warning("CONTROL_TOTAL", null,
                            "file declares " + expected + " line items but " + actual + " were read"));
                }
            } catch (NumberFormatException e) {
                issues.add(Violation.warning("CONTROL_TOTAL", null, "control count '" + count + "' is not a number"));
            }
        }
        return issues;
    }
}
