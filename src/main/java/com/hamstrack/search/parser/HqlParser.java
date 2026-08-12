package com.hamstrack.search.parser;

import com.hamstrack.search.parser.ast.ComparisonOp;
import com.hamstrack.search.parser.ast.Expr;
import com.hamstrack.search.parser.ast.OrderBy;
import com.hamstrack.search.parser.ast.Query;
import com.hamstrack.search.parser.ast.Value;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Recursive-descent HQL parser (Advanced Search proposal §4.2). Consumes the {@link Lexer}'s token
 * stream and produces a field-name-agnostic {@link Query} AST, or throws an {@link HqlParseException}
 * (position-carrying) on any lexical or grammatical error.
 *
 * <p>Precedence, highest → lowest: parentheses → {@code NOT} → {@code AND} → {@code OR}
 * ({@code orExpr = andExpr {OR andExpr}}, {@code andExpr = notExpr {AND notExpr}},
 * {@code notExpr = NOT notExpr | primary}). {@code AND}/{@code OR} are left-associative. An optional
 * {@code ORDER BY} must be the last clause. An empty / whitespace-only input parses to
 * {@link Query#all()}.
 *
 * <p>DoS limits (§9), each raising a specific {@link HqlParseException}: query length ≤ 2000,
 * nesting depth ≤ 32, IN-list ≤ 200, ORDER BY keys ≤ 5, leaf predicates ≤ 50.
 *
 * <p>Stateless entry point: {@link #parse(String)}. Instances are single-use and not thread-safe.
 */
public final class HqlParser {

    /** Maximum accepted query string length, in characters (§9). */
    public static final int MAX_QUERY_LENGTH = 2000;
    /** Maximum expression nesting depth (§9). */
    public static final int MAX_DEPTH = 32;
    /** Maximum number of values in an {@code IN (...)} list (§9). */
    public static final int MAX_IN_LIST = 200;
    /** Maximum number of {@code ORDER BY} sort keys (§9). */
    public static final int MAX_SORT_KEYS = 5;
    /** Maximum number of leaf predicates (comparisons / IN / IS EMPTY) per query (§9). */
    public static final int MAX_PREDICATES = 50;

    private final List<Token> tokens;
    private int index;
    private int predicateCount;

    private HqlParser(List<Token> tokens) {
        this.tokens = tokens;
    }

    /**
     * Parse a raw HQL string into a {@link Query} AST.
     *
     * @throws HqlParseException on any lexer/parser error (carries position/length/token)
     */
    public static Query parse(String hql) {
        String input = hql == null ? "" : hql;
        if (input.length() > MAX_QUERY_LENGTH) {
            throw new HqlParseException(HqlParseException.Kind.QUERY_TOO_LONG,
                    "Query is too long (" + input.length() + " chars; max " + MAX_QUERY_LENGTH + ")",
                    MAX_QUERY_LENGTH, input.length() - MAX_QUERY_LENGTH, null);
        }
        List<Token> tokens = Lexer.tokenize(input);
        return new HqlParser(tokens).parseQuery();
    }

    // ---- grammar: query = expr? orderBy? ----

    private Query parseQuery() {
        // Empty / whitespace-only → just EOF → "all".
        if (peek().type() == TokenType.EOF) {
            return Query.all();
        }

        Optional<Expr> filter = Optional.empty();
        // An ORDER BY may appear with no preceding filter (a pure sort). Otherwise parse a filter.
        if (peek().type() != TokenType.ORDER) {
            filter = Optional.of(parseExpr(0));
        }

        Optional<OrderBy> orderBy = Optional.empty();
        if (peek().type() == TokenType.ORDER) {
            orderBy = Optional.of(parseOrderBy());
        }

        // Anything left over is trailing junk. If it's an ORDER-BY keyword reached after a filter
        // that already consumed one, or stray tokens, report against the current token.
        Token rest = peek();
        if (rest.type() != TokenType.EOF) {
            // A second ORDER BY, or tokens after ORDER BY, means ORDER BY was not the last clause.
            if (rest.type() == TokenType.ORDER || orderBy.isPresent()) {
                throw error(HqlParseException.Kind.ORDER_BY_NOT_LAST,
                        "ORDER BY must be the last clause", rest);
            }
            throw error(HqlParseException.Kind.TRAILING_INPUT,
                    "Unexpected input after query", rest);
        }
        return new Query(filter, orderBy);
    }

    // ---- boolean expression with precedence NOT > AND > OR ----

    private Expr parseExpr(int depth) {
        return parseOr(depth);
    }

    private Expr parseOr(int depth) {
        checkDepth(depth);
        Expr left = parseAnd(depth + 1);
        while (peek().type() == TokenType.OR) {
            advance();
            Expr right = parseAnd(depth + 1);
            left = new Expr.Or(left, right);
        }
        return left;
    }

    private Expr parseAnd(int depth) {
        checkDepth(depth);
        Expr left = parseNot(depth + 1);
        while (peek().type() == TokenType.AND) {
            advance();
            Expr right = parseNot(depth + 1);
            left = new Expr.And(left, right);
        }
        return left;
    }

    private Expr parseNot(int depth) {
        checkDepth(depth);
        if (peek().type() == TokenType.NOT) {
            advance();
            return new Expr.Not(parseNot(depth + 1));
        }
        return parsePrimary(depth + 1);
    }

    private Expr parsePrimary(int depth) {
        checkDepth(depth);
        Token t = peek();
        if (t.type() == TokenType.LPAREN) {
            advance();
            Expr inner = parseExpr(depth + 1);
            expect(TokenType.RPAREN, HqlParseException.Kind.UNBALANCED_PARENS,
                    "Expected ')'");
            return inner;
        }
        return parsePredicate();
    }

    // ---- predicates: comparison | inClause | emptyClause | textMatch ----

    private Expr parsePredicate() {
        Token fieldTok = peek();
        if (fieldTok.type() != TokenType.IDENT) {
            throw error(HqlParseException.Kind.UNEXPECTED_TOKEN,
                    "Expected a field name", fieldTok);
        }
        advance();
        String field = fieldTok.text();
        countPredicate(fieldTok);

        Token next = peek();
        switch (next.type()) {
            case OP -> {
                advance();
                ComparisonOp op = ComparisonOp.fromSymbol(next.text());
                // fromSymbol never returns null here: the lexer only emits the seven known symbols.
                Value value = parseValue();
                return new Expr.Comparison(field, op, value);
            }
            case IN -> {
                advance();
                return parseInList(field);
            }
            case IS -> {
                advance();
                return parseEmptyClause(field);
            }
            default -> throw error(HqlParseException.Kind.UNEXPECTED_TOKEN,
                    "Expected an operator, IN, or IS after field '" + field + "'", next);
        }
    }

    private Expr parseInList(String field) {
        expect(TokenType.LPAREN, HqlParseException.Kind.UNEXPECTED_TOKEN,
                "Expected '(' after IN");
        List<Value> values = new ArrayList<>();
        if (peek().type() == TokenType.RPAREN) {
            throw error(HqlParseException.Kind.EMPTY_IN_LIST,
                    "IN list must contain at least one value", peek());
        }
        values.add(parseValue());
        while (peek().type() == TokenType.COMMA) {
            advance();
            if (values.size() >= MAX_IN_LIST) {
                Token offending = peek();
                throw error(HqlParseException.Kind.IN_LIST_TOO_LARGE,
                        "IN list too large (max " + MAX_IN_LIST + ")", offending);
            }
            values.add(parseValue());
        }
        expect(TokenType.RPAREN, HqlParseException.Kind.UNBALANCED_PARENS,
                "Expected ',' or ')' in IN list");
        return new Expr.InList(field, values);
    }

    private Expr parseEmptyClause(String field) {
        boolean negated = false;
        if (peek().type() == TokenType.NOT) {
            advance();
            negated = true;
        }
        expect(TokenType.EMPTY, HqlParseException.Kind.UNEXPECTED_TOKEN,
                "Expected EMPTY after IS" + (negated ? " NOT" : ""));
        return new Expr.IsEmpty(field, negated);
    }

    // ---- values: STRING | NUMBER | funcCall ----

    private Value parseValue() {
        Token t = peek();
        switch (t.type()) {
            case STRING -> {
                advance();
                return new Value.StringLiteral(t.text());
            }
            case NUMBER -> {
                advance();
                return new Value.NumberLiteral(t.text());
            }
            case IDENT -> {
                // Only a function call is a legal identifier-shaped value. A bare word (e.g.
                // `status = In Progress`) is the classic unquoted-value mistake — targeted error.
                if (peekAt(1).type() == TokenType.LPAREN) {
                    return parseFunctionCall();
                }
                throw error(HqlParseException.Kind.UNQUOTED_VALUE,
                        "String value must be quoted", t);
            }
            case EMPTY -> throw error(HqlParseException.Kind.UNEXPECTED_TOKEN,
                    "EMPTY is only allowed after IS", t);
            default -> {
                // NOTE: a bare word that happens to spell a keyword (the spec's own example
                // `status = In Progress` — "In" lexes as the IN keyword) is still an unquoted-value
                // mistake, so give the same targeted quoting hint rather than a generic message.
                if (isBarewordKeyword(t.type())) {
                    throw error(HqlParseException.Kind.UNQUOTED_VALUE,
                            "String value must be quoted", t);
                }
                throw error(HqlParseException.Kind.UNEXPECTED_TOKEN,
                        "Expected a value (quoted string, number, or function call)", t);
            }
        }
    }

    /** Keyword token types that can appear where a user meant an unquoted bareword value. */
    private static boolean isBarewordKeyword(TokenType type) {
        return switch (type) {
            case AND, OR, NOT, IN, IS, ORDER, BY, ASC, DESC -> true;
            default -> false;
        };
    }

    private Value parseFunctionCall() {
        Token nameTok = peek();
        advance();
        expect(TokenType.LPAREN, HqlParseException.Kind.UNEXPECTED_TOKEN,
                "Expected '(' after function name");
        List<Value> args = new ArrayList<>();
        if (peek().type() != TokenType.RPAREN) {
            args.add(parseValue());
            while (peek().type() == TokenType.COMMA) {
                advance();
                args.add(parseValue());
            }
        }
        expect(TokenType.RPAREN, HqlParseException.Kind.UNBALANCED_PARENS,
                "Expected ')' to close function call");
        return new Value.FunctionCall(nameTok.text(), args);
    }

    // ---- ORDER BY ----

    private OrderBy parseOrderBy() {
        expect(TokenType.ORDER, HqlParseException.Kind.UNEXPECTED_TOKEN, "Expected ORDER");
        expect(TokenType.BY, HqlParseException.Kind.UNEXPECTED_TOKEN, "Expected BY after ORDER");

        List<OrderBy.SortKey> keys = new ArrayList<>();
        keys.add(parseSortKey());
        while (peek().type() == TokenType.COMMA) {
            advance();
            if (keys.size() >= MAX_SORT_KEYS) {
                throw error(HqlParseException.Kind.TOO_MANY_SORT_KEYS,
                        "Too many ORDER BY keys (max " + MAX_SORT_KEYS + ")", peek());
            }
            keys.add(parseSortKey());
        }
        return new OrderBy(keys);
    }

    private OrderBy.SortKey parseSortKey() {
        Token fieldTok = peek();
        if (fieldTok.type() != TokenType.IDENT) {
            throw error(HqlParseException.Kind.UNEXPECTED_TOKEN,
                    "Expected a sort field", fieldTok);
        }
        advance();
        OrderBy.Direction dir = OrderBy.Direction.ASC; // default ASC (§4.2)
        if (peek().type() == TokenType.ASC) {
            advance();
        } else if (peek().type() == TokenType.DESC) {
            advance();
            dir = OrderBy.Direction.DESC;
        }
        return new OrderBy.SortKey(fieldTok.text(), dir);
    }

    // ---- limits & token helpers ----

    private void checkDepth(int depth) {
        if (depth > MAX_DEPTH) {
            throw error(HqlParseException.Kind.DEPTH_LIMIT_EXCEEDED,
                    "Expression nested too deeply (max depth " + MAX_DEPTH + ")", peek());
        }
    }

    private void countPredicate(Token at) {
        predicateCount++;
        if (predicateCount > MAX_PREDICATES) {
            throw error(HqlParseException.Kind.TOO_MANY_PREDICATES,
                    "Too many predicates (max " + MAX_PREDICATES + ")", at);
        }
    }

    private Token peek() {
        return tokens.get(index);
    }

    private Token peekAt(int ahead) {
        int i = index + ahead;
        return i < tokens.size() ? tokens.get(i) : tokens.get(tokens.size() - 1);
    }

    private void advance() {
        if (index < tokens.size() - 1) {
            index++;
        }
    }

    private void expect(TokenType type, HqlParseException.Kind kind, String message) {
        Token t = peek();
        if (t.type() != type) {
            throw error(kind, message, t);
        }
        advance();
    }

    /** Build a positioned parse error anchored on {@code at} (uses its offset/length/text). */
    private HqlParseException error(HqlParseException.Kind kind, String message, Token at) {
        String token = at.type() == TokenType.EOF ? null : at.text();
        String detail = message + " at position " + at.start();
        return new HqlParseException(kind, detail, at.start(), at.length(), token);
    }
}
