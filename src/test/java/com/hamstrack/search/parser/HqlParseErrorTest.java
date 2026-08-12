package com.hamstrack.search.parser;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Error-model tests: every parse failure must carry the correct {@code position} (and the right
 * {@link HqlParseException.Kind}) so pass 2 can render the ProblemDetail highlight (§7.1).
 */
class HqlParseErrorTest {

    private HqlParseException parseFail(String hql) {
        return assertThrows(HqlParseException.class, () -> HqlParser.parse(hql));
    }

    @Test
    void everyParseErrorReportsPartError() {
        HqlParseException ex = parseFail("status = \"oops");
        assertEquals("PARSE_ERROR", ex.getErrorType());
    }

    @Test
    void unterminatedString() {
        HqlParseException ex = parseFail("status = \"In Progress");
        assertEquals(HqlParseException.Kind.UNTERMINATED_STRING, ex.getKind());
        assertEquals(9, ex.getPosition());
    }

    @Test
    void unquotedValue() {
        // "status = In Progress" — 'In' at offset 9 must be quoted
        HqlParseException ex = parseFail("status = In Progress");
        assertEquals(HqlParseException.Kind.UNQUOTED_VALUE, ex.getKind());
        assertEquals(9, ex.getPosition());
        assertEquals("In", ex.getToken());
    }

    @Test
    void unknownOperatorBangIsBadChar() {
        HqlParseException ex = parseFail("a ! 1");
        assertEquals(HqlParseException.Kind.BAD_CHARACTER, ex.getKind());
        assertEquals(2, ex.getPosition());
    }

    @Test
    void unbalancedParens() {
        HqlParseException ex = parseFail("(a = 1 AND b = 2");
        assertEquals(HqlParseException.Kind.UNBALANCED_PARENS, ex.getKind());
        // error anchored at EOF (offset = input length)
        assertEquals(16, ex.getPosition());
    }

    @Test
    void trailingJunk() {
        HqlParseException ex = parseFail("a = 1 b = 2");
        assertEquals(HqlParseException.Kind.TRAILING_INPUT, ex.getKind());
        assertEquals(6, ex.getPosition());
        assertEquals("b", ex.getToken());
    }

    @Test
    void orderByNotLast() {
        // a filter after ORDER BY
        HqlParseException ex = parseFail("ORDER BY a AND b = 2");
        assertEquals(HqlParseException.Kind.ORDER_BY_NOT_LAST, ex.getKind());
        assertEquals(11, ex.getPosition());
    }

    @Test
    void secondOrderBy() {
        HqlParseException ex = parseFail("a = 1 ORDER BY x ORDER BY y");
        assertEquals(HqlParseException.Kind.ORDER_BY_NOT_LAST, ex.getKind());
        assertEquals(17, ex.getPosition());
    }

    @Test
    void emptyInList() {
        HqlParseException ex = parseFail("status IN ()");
        assertEquals(HqlParseException.Kind.EMPTY_IN_LIST, ex.getKind());
        assertEquals(11, ex.getPosition());
    }

    @Test
    void emptyAsPlainValueIsRejected() {
        HqlParseException ex = parseFail("assignee = EMPTY");
        assertEquals(HqlParseException.Kind.UNEXPECTED_TOKEN, ex.getKind());
        assertEquals(11, ex.getPosition());
    }

    @Test
    void inListOverTwoHundredLimit() {
        StringBuilder sb = new StringBuilder("status IN (1");
        for (int i = 1; i < 201; i++) { // 201 values total → exceeds 200
            sb.append(",").append(i);
        }
        sb.append(")");
        HqlParseException ex = parseFail(sb.toString());
        assertEquals(HqlParseException.Kind.IN_LIST_TOO_LARGE, ex.getKind());
        assertTrue(ex.getPosition() > 0);
    }

    @Test
    void inListAtExactlyTwoHundredIsOk() {
        StringBuilder sb = new StringBuilder("status IN (1");
        for (int i = 1; i < 200; i++) {
            sb.append(",").append(i);
        }
        sb.append(")");
        // exactly 200 values — must parse
        assertTrue(HqlParser.parse(sb.toString()).filter().isPresent());
    }

    @Test
    void queryLengthLimitExceeded() {
        String longQuery = "a = ".repeat(600); // > 2000 chars
        assertTrue(longQuery.length() > HqlParser.MAX_QUERY_LENGTH);
        HqlParseException ex = parseFail(longQuery);
        assertEquals(HqlParseException.Kind.QUERY_TOO_LONG, ex.getKind());
        assertEquals(HqlParser.MAX_QUERY_LENGTH, ex.getPosition());
    }

    @Test
    void depthLimitExceeded() {
        // deeply nested parentheses blow past MAX_DEPTH
        int n = HqlParser.MAX_DEPTH + 10;
        String hql = "(".repeat(n) + "a = 1" + ")".repeat(n);
        HqlParseException ex = parseFail(hql);
        assertEquals(HqlParseException.Kind.DEPTH_LIMIT_EXCEEDED, ex.getKind());
    }

    @Test
    void tooManySortKeys() {
        HqlParseException ex = parseFail("a = 1 ORDER BY b, c, d, e, f, g");
        assertEquals(HqlParseException.Kind.TOO_MANY_SORT_KEYS, ex.getKind());
    }

    @Test
    void tooManyPredicates() {
        StringBuilder sb = new StringBuilder("a0 = 0");
        for (int i = 1; i <= HqlParser.MAX_PREDICATES; i++) { // 51 predicates total
            sb.append(" AND a").append(i).append(" = ").append(i);
        }
        HqlParseException ex = parseFail(sb.toString());
        assertEquals(HqlParseException.Kind.TOO_MANY_PREDICATES, ex.getKind());
    }

    @Test
    void missingValueAfterOperator() {
        HqlParseException ex = parseFail("a =");
        assertEquals(HqlParseException.Kind.UNEXPECTED_TOKEN, ex.getKind());
        assertEquals(3, ex.getPosition()); // EOF at end
    }

    @Test
    void fieldWithoutOperator() {
        HqlParseException ex = parseFail("status");
        assertEquals(HqlParseException.Kind.UNEXPECTED_TOKEN, ex.getKind());
    }
}
