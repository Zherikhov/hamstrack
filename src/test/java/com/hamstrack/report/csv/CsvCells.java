package com.hamstrack.report.csv;

import java.util.ArrayList;
import java.util.List;

/**
 * <strong>A CSV line read back as cells</strong> — the reader half of {@link CsvWriter}, for
 * tests.
 *
 * <p>Round 2 turned on the fact that nothing in the suite could answer "how many cells does this
 * line have?". The comment-header injection (item 1) was invisible because every assertion
 * compared whole lines as strings, and the row-arity gap (item 5) was invisible for the same
 * reason. {@code split(",")} cannot answer it either: it is wrong on any row carrying a title with
 * a comma, which is the exact row worth checking.
 *
 * <p>So this parses rather than splits — quotes respected, doubled quotes undoubled. It is
 * deliberately RFC 4180 for a <em>single line</em>: the writer flattens newlines out of comment
 * values and quotes text cells, so a physical line is a record here. If that ever stops being
 * true, a test asserting one cell per comment line is what will say so.
 */
public final class CsvCells {

    private CsvCells() {
    }

    /** The cells of one CSV line, unescaped. */
    public static List<String> of(String line) {
        var cells = new ArrayList<String>();
        var cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c != '"') {
                    cell.append(c);
                } else if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else {
                    quoted = false;
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                cells.add(cell.toString());
                cell.setLength(0);
            } else {
                cell.append(c);
            }
        }
        cells.add(cell.toString());
        return cells;
    }

    /** A single quoted field's value: outer quotes removed, doubled quotes undoubled. */
    public static String unquote(String field) {
        if (field.length() < 2 || field.charAt(0) != '"' || field.charAt(field.length() - 1) != '"') {
            return field;
        }
        return field.substring(1, field.length() - 1).replace("\"\"", "\"");
    }
}
