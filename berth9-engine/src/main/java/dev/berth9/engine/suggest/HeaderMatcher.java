package dev.berth9.engine.suggest;

import dev.berth9.engine.model.TargetField;
import dev.berth9.engine.text.JaroWinkler;
import dev.berth9.engine.text.Texts;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Scores how well a partner header names a target field, after expanding the abbreviations suppliers
 * love ("Inv Dt", "Qty", "Amt", "PO Ref"). Combines exact phrase match, token overlap (Dice),
 * containment ("Qty (Nos)" contains "qty") and Jaro-Winkler for typos ("Quantiy").
 */
public final class HeaderMatcher {

    private static final Map<String, String> ABBREVIATIONS = Map.ofEntries(
            Map.entry("inv", "invoice"), Map.entry("invc", "invoice"), Map.entry("bill", "invoice"),
            Map.entry("no", "number"), Map.entry("num", "number"), Map.entry("nbr", "number"), Map.entry("#", "number"),
            Map.entry("dt", "date"), Map.entry("dated", "date"),
            Map.entry("qty", "quantity"), Map.entry("qnty", "quantity"), Map.entry("quantiy", "quantity"),
            Map.entry("amt", "amount"), Map.entry("val", "value"),
            Map.entry("desc", "description"), Map.entry("descr", "description"),
            Map.entry("ccy", "currency"), Map.entry("curr", "currency"), Map.entry("cur", "currency"),
            Map.entry("ln", "line"), Map.entry("sr", "serial"), Map.entry("sl", "serial"),
            Map.entry("prc", "price"), Map.entry("ext", "extended"), Map.entry("itm", "item"),
            Map.entry("prod", "product"), Map.entry("mat", "material"), Map.entry("matl", "material"),
            Map.entry("vend", "vendor"), Map.entry("supp", "supplier"), Map.entry("sup", "supplier"),
            Map.entry("ord", "order"), Map.entry("purch", "purchase"), Map.entry("ref", "reference"),
            Map.entry("id", "id"), Map.entry("pc", "piece"), Map.entry("pcs", "pieces"),
            Map.entry("orderid", "order id"), Map.entry("invoiceid", "invoice id"));

    private static final Set<String> STOPWORDS = Set.of("of", "the", "a", "an", "in", "for", "per", "by", "to", "rs", "inr", "usd", "₹", "$");

    private HeaderMatcher() {
    }

    /** Best similarity in [0, 1] between a column name and any name, label or alias of the field. */
    public static double score(String column, TargetField field) {
        List<String> fieldPhrases = new ArrayList<>();
        fieldPhrases.add(normalize(field.name()));
        fieldPhrases.add(normalize(field.label()));
        field.aliases().forEach(a -> fieldPhrases.add(normalize(a)));
        double best = 0;
        for (String variant : variants(column)) {
            for (String phrase : fieldPhrases) {
                best = Math.max(best, similarity(variant, phrase));
                if (best >= 1.0) {
                    return 1.0;
                }
            }
        }
        return best;
    }

    /** The header as written plus, for XML paths and X12 keys, the forms that carry the meaning. */
    static List<String> variants(String column) {
        Set<String> out = new LinkedHashSet<>();
        String described = X12Dictionary.describe(column);
        if (described != null) {
            out.add(normalize(described));
        }
        if (column.contains("/")) {
            String[] parts = column.split("/");
            String last = parts[parts.length - 1].replace("@", "");
            out.add(normalize(last));
            if (parts.length >= 2) {
                out.add(normalize(parts[parts.length - 2] + " " + last));
            }
        } else {
            out.add(normalize(column.replace("@", "")));
        }
        out.remove("");
        return new ArrayList<>(out);
    }

    public static String normalize(String text) {
        String key = Texts.normalizeKey(text);
        if (key.isEmpty()) {
            return key;
        }
        List<String> tokens = new ArrayList<>();
        for (String token : key.split(" ")) {
            String expanded = ABBREVIATIONS.getOrDefault(token, token);
            for (String t : expanded.split(" ")) {
                if (!STOPWORDS.contains(t) && !t.isBlank()) {
                    tokens.add(t);
                }
            }
        }
        return String.join(" ", tokens);
    }

    static double similarity(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        if (a.equals(b)) {
            return 1.0;
        }
        Set<String> ta = new LinkedHashSet<>(List.of(a.split(" ")));
        Set<String> tb = new LinkedHashSet<>(List.of(b.split(" ")));
        Set<String> common = new LinkedHashSet<>(ta);
        common.retainAll(tb);
        double dice = 2.0 * common.size() / (ta.size() + tb.size());
        double containment = 0;
        if (common.size() == tb.size()) {
            containment = 0.75 + 0.2 * tb.size() / (double) Math.max(ta.size(), tb.size());
        } else if (common.size() == ta.size()) {
            containment = 0.75 + 0.2 * ta.size() / (double) Math.max(ta.size(), tb.size());
        }
        double jw = JaroWinkler.similarity(a, b);
        double fuzzy = jw >= 0.9 ? jw * 0.95 : 0;
        return Math.max(dice, Math.max(containment, fuzzy));
    }
}
