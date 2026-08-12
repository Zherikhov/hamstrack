package com.hamstrack.search.parser;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure unit tests for the HQL {@link Lexer} (no Spring, no DB). */
class LexerTest {

    private List<Token> lex(String s) {
        return Lexer.tokenize(s);
    }

    @Test
    void tokenizesOperatorsIncludingTwoCharForms() {
        List<Token> t = lex("a >= b <= c != d = e ~ f > g < h");
        // fields and ops interleave; assert the operator token texts in order
        List<String> ops = t.stream()
                .filter(tok -> tok.type() == TokenType.OP)
                .map(Token::text)
                .toList();
        assertEquals(List.of(">=", "<=", "!=", "=", "~", ">", "<"), ops);
    }

    @Test
    void keywordsAreCaseInsensitive() {
        List<Token> t = lex("and OR nOt In Is eMpTy OrDeR bY aSc DeSc");
        List<TokenType> types = t.stream().map(Token::type).filter(x -> x != TokenType.EOF).toList();
        assertEquals(List.of(
                TokenType.AND, TokenType.OR, TokenType.NOT, TokenType.IN, TokenType.IS,
                TokenType.EMPTY, TokenType.ORDER, TokenType.BY, TokenType.ASC, TokenType.DESC), types);
    }

    @Test
    void identifierIsCasePreserving() {
        List<Token> t = lex("MyField");
        assertEquals(TokenType.IDENT, t.get(0).type());
        assertEquals("MyField", t.get(0).text());
    }

    @Test
    void doubleQuotedStringUnescapes() {
        List<Token> t = lex("\"In \\\"Progress\\\"\"");
        assertEquals(TokenType.STRING, t.get(0).type());
        assertEquals("In \"Progress\"", t.get(0).text());
        // span covers the whole quoted source including escapes
        assertEquals(0, t.get(0).start());
    }

    @Test
    void singleQuotedStringWithEscapes() {
        List<Token> t = lex("'a\\'b\\\\c'");
        assertEquals("a'b\\c", t.get(0).text());
    }

    @Test
    void numbersIncludingNegativeAndDecimal() {
        List<Token> t = lex("42 -3 5.5 -0.25");
        List<String> nums = t.stream()
                .filter(tok -> tok.type() == TokenType.NUMBER)
                .map(Token::text)
                .toList();
        assertEquals(List.of("42", "-3", "5.5", "-0.25"), nums);
    }

    @Test
    void parensAndComma() {
        List<Token> t = lex("(a,b)");
        assertEquals(TokenType.LPAREN, t.get(0).type());
        assertEquals(TokenType.COMMA, t.get(2).type());
        assertEquals(TokenType.RPAREN, t.get(4).type());
    }

    @Test
    void endsWithEofAtInputLength() {
        String s = "status = \"x\"";
        List<Token> t = lex(s);
        Token eof = t.get(t.size() - 1);
        assertEquals(TokenType.EOF, eof.type());
        assertEquals(s.length(), eof.start());
    }

    @Test
    void unterminatedStringReportsStartOffset() {
        HqlParseException ex = assertThrows(HqlParseException.class, () -> lex("status = \"oops"));
        assertEquals(HqlParseException.Kind.UNTERMINATED_STRING, ex.getKind());
        assertEquals(9, ex.getPosition());
    }

    @Test
    void illegalEscapeReportsOffset() {
        HqlParseException ex = assertThrows(HqlParseException.class, () -> lex("\"a\\nb\""));
        assertEquals(HqlParseException.Kind.ILLEGAL_ESCAPE, ex.getKind());
        assertEquals(2, ex.getPosition());
    }

    @Test
    void badCharacterReportsOffset() {
        HqlParseException ex = assertThrows(HqlParseException.class, () -> lex("a = @"));
        assertEquals(HqlParseException.Kind.BAD_CHARACTER, ex.getKind());
        assertEquals(4, ex.getPosition());
        assertEquals("@", ex.getToken());
    }

    @Test
    void loneBangIsBadCharacter() {
        HqlParseException ex = assertThrows(HqlParseException.class, () -> lex("a ! b"));
        assertEquals(HqlParseException.Kind.BAD_CHARACTER, ex.getKind());
        assertEquals(2, ex.getPosition());
    }

    @Test
    void emptyInputIsJustEof() {
        List<Token> t = lex("   ");
        assertEquals(1, t.size());
        assertTrue(t.get(0).type() == TokenType.EOF);
    }
}
