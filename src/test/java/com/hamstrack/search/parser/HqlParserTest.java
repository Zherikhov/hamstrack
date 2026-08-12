package com.hamstrack.search.parser;

import com.hamstrack.search.parser.ast.ComparisonOp;
import com.hamstrack.search.parser.ast.Expr;
import com.hamstrack.search.parser.ast.OrderBy;
import com.hamstrack.search.parser.ast.Query;
import com.hamstrack.search.parser.ast.Value;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure unit tests for {@link HqlParser} (no Spring, no DB). */
class HqlParserTest {

    // ---- canonical epic query ----

    @Test
    void canonicalEpicQuery() {
        Query q = HqlParser.parse(
                "status = \"In Progress\" AND assignee = currentUser() ORDER BY priority DESC");

        Expr.And and = assertInstanceOf(Expr.And.class, q.filter().orElseThrow());

        Expr.Comparison status = assertInstanceOf(Expr.Comparison.class, and.left());
        assertEquals("status", status.field());
        assertEquals(ComparisonOp.EQ, status.op());
        assertEquals(new Value.StringLiteral("In Progress"), status.value());

        Expr.Comparison assignee = assertInstanceOf(Expr.Comparison.class, and.right());
        assertEquals("assignee", assignee.field());
        Value.FunctionCall fn = assertInstanceOf(Value.FunctionCall.class, assignee.value());
        assertEquals("currentUser", fn.name());
        assertTrue(fn.args().isEmpty());

        OrderBy ob = q.orderBy().orElseThrow();
        assertEquals(1, ob.keys().size());
        assertEquals("priority", ob.keys().get(0).field());
        assertEquals(OrderBy.Direction.DESC, ob.keys().get(0).direction());
    }

    // ---- precedence: NOT > AND > OR ----

    @Test
    void andBindsTighterThanOr() {
        // a = 1 OR b = 2 AND c = 3  =>  a=1 OR (b=2 AND c=3)
        Query q = HqlParser.parse("a = 1 OR b = 2 AND c = 3");
        Expr.Or or = assertInstanceOf(Expr.Or.class, q.filter().orElseThrow());
        assertInstanceOf(Expr.Comparison.class, or.left());     // a = 1
        Expr.And and = assertInstanceOf(Expr.And.class, or.right());
        assertEquals("b", ((Expr.Comparison) and.left()).field());
        assertEquals("c", ((Expr.Comparison) and.right()).field());
    }

    @Test
    void notBindsTighterThanAnd() {
        // NOT a = 1 AND b = 2  =>  (NOT a=1) AND b=2
        Query q = HqlParser.parse("NOT a = 1 AND b = 2");
        Expr.And and = assertInstanceOf(Expr.And.class, q.filter().orElseThrow());
        Expr.Not not = assertInstanceOf(Expr.Not.class, and.left());
        assertEquals("a", ((Expr.Comparison) not.operand()).field());
        assertEquals("b", ((Expr.Comparison) and.right()).field());
    }

    @Test
    void parenthesesOverridePrecedence() {
        // (a = 1 OR b = 2) AND c = 3  =>  AND( OR(a,b), c )
        Query q = HqlParser.parse("(a = 1 OR b = 2) AND c = 3");
        Expr.And and = assertInstanceOf(Expr.And.class, q.filter().orElseThrow());
        assertInstanceOf(Expr.Or.class, and.left());
        assertEquals("c", ((Expr.Comparison) and.right()).field());
    }

    @Test
    void andIsLeftAssociative() {
        // a = 1 AND b = 2 AND c = 3 => And(And(a,b),c)
        Query q = HqlParser.parse("a = 1 AND b = 2 AND c = 3");
        Expr.And outer = assertInstanceOf(Expr.And.class, q.filter().orElseThrow());
        assertInstanceOf(Expr.And.class, outer.left());
        assertEquals("c", ((Expr.Comparison) outer.right()).field());
    }

    // ---- predicate kinds ----

    @Test
    void inListWithMixedValues() {
        Query q = HqlParser.parse("status IN (\"To Do\", \"Done\", 3)");
        Expr.InList in = assertInstanceOf(Expr.InList.class, q.filter().orElseThrow());
        assertEquals("status", in.field());
        assertEquals(3, in.values().size());
        assertEquals(new Value.StringLiteral("To Do"), in.values().get(0));
        assertEquals(new Value.NumberLiteral("3"), in.values().get(2));
    }

    @Test
    void isEmptyAndIsNotEmpty() {
        Expr.IsEmpty empty = assertInstanceOf(Expr.IsEmpty.class,
                HqlParser.parse("assignee IS EMPTY").filter().orElseThrow());
        assertEquals("assignee", empty.field());
        assertFalse(empty.negated());

        Expr.IsEmpty notEmpty = assertInstanceOf(Expr.IsEmpty.class,
                HqlParser.parse("due IS NOT EMPTY").filter().orElseThrow());
        assertTrue(notEmpty.negated());
    }

    @Test
    void textMatchOperator() {
        Expr.Comparison c = assertInstanceOf(Expr.Comparison.class,
                HqlParser.parse("text ~ \"login bug\"").filter().orElseThrow());
        assertEquals(ComparisonOp.MATCH, c.op());
        assertEquals(new Value.StringLiteral("login bug"), c.value());
    }

    @Test
    void allComparisonOperators() {
        assertEquals(ComparisonOp.NEQ, comp("a != 1").op());
        assertEquals(ComparisonOp.GT, comp("a > 1").op());
        assertEquals(ComparisonOp.LT, comp("a < 1").op());
        assertEquals(ComparisonOp.GTE, comp("a >= 1").op());
        assertEquals(ComparisonOp.LTE, comp("a <= 1").op());
    }

    @Test
    void escapedQuotesInStringValue() {
        Expr.Comparison c = comp("name = \"say \\\"hi\\\"\"");
        assertEquals(new Value.StringLiteral("say \"hi\""), c.value());
    }

    @Test
    void functionCallValue() {
        Expr.Comparison c = comp("created > now()");
        Value.FunctionCall fn = assertInstanceOf(Value.FunctionCall.class, c.value());
        assertEquals("now", fn.name());
    }

    // ---- ORDER BY ----

    @Test
    void orderBySingleDefaultAsc() {
        Query q = HqlParser.parse("a = 1 ORDER BY priority");
        OrderBy ob = q.orderBy().orElseThrow();
        assertEquals(OrderBy.Direction.ASC, ob.keys().get(0).direction());
    }

    @Test
    void orderByMultiKeyMixedDirections() {
        Query q = HqlParser.parse("a = 1 ORDER BY priority DESC, updated ASC, created");
        OrderBy ob = q.orderBy().orElseThrow();
        assertEquals(3, ob.keys().size());
        assertEquals(OrderBy.Direction.DESC, ob.keys().get(0).direction());
        assertEquals(OrderBy.Direction.ASC, ob.keys().get(1).direction());
        assertEquals(OrderBy.Direction.ASC, ob.keys().get(2).direction());
    }

    @Test
    void orderByWithoutFilterIsPureSort() {
        Query q = HqlParser.parse("ORDER BY updated DESC");
        assertTrue(q.filter().isEmpty());
        assertEquals("updated", q.orderBy().orElseThrow().keys().get(0).field());
    }

    // ---- empty query ----

    @Test
    void emptyStringIsAll() {
        Query q = HqlParser.parse("");
        assertTrue(q.isEmpty());
        assertTrue(q.filter().isEmpty());
        assertTrue(q.orderBy().isEmpty());
    }

    @Test
    void whitespaceOnlyIsAll() {
        assertTrue(HqlParser.parse("   \t  ").isEmpty());
    }

    @Test
    void nullIsAll() {
        assertTrue(HqlParser.parse(null).isEmpty());
    }

    // ---- helper ----

    private Expr.Comparison comp(String hql) {
        return assertInstanceOf(Expr.Comparison.class,
                HqlParser.parse(hql).filter().orElseThrow());
    }
}
