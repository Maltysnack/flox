package flox;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class Scanner {
    private static final Map<String, TokenType> keywords;

    static {
        keywords = new HashMap<>();
        keywords.put("system", TokenType.SYSTEM);
        keywords.put("map", TokenType.MAP);
        keywords.put("list", TokenType.LIST);
        keywords.put("print", TokenType.PRINT);
        keywords.put("help", TokenType.HELP);
        keywords.put("river", TokenType.RIVER);
        keywords.put("dam", TokenType.DAM);
        keywords.put("outlet", TokenType.OUTLET);
        keywords.put("node", TokenType.NODE);
        keywords.put("mode", TokenType.MODE);
        keywords.put("start", TokenType.START);
        keywords.put("disable", TokenType.DISABLE);
        keywords.put("compare", TokenType.COMPARE);
        keywords.put("as", TokenType.AS);
        keywords.put("with", TokenType.WITH);
    }

    private final String source;
    private final List<Token> tokens = new ArrayList<>();
    private int start = 0;
    private int current = 0;
    private int line = 1;

    Scanner(String source) {
        this.source = source;
    }

    List<Token> scanTokens() {
        while (!isAtEnd()) {
            start = current;
            scanToken();
        }

        tokens.add(new Token(TokenType.EOF, "", null, line));
        return tokens;
    }

    private void scanToken() {
        char c = advance();
        switch (c) {
            // Removed all operators except ->, brackets, comma, semicolon
            case '[':
                addToken(TokenType.LEFT_BRACKET);
                break;
            case ']':
                addToken(TokenType.RIGHT_BRACKET);
                break;
            case '{':
                addToken(TokenType.LEFT_BRACE);
                break;
            case '}':
                addToken(TokenType.RIGHT_BRACE);
                break;
            case ',':
                addToken(TokenType.COMMA);
                break;
            case ';':
                addToken(TokenType.SEMICOLON);
                break;
            case '%':
                addToken(TokenType.PERCENT);
                break;
            case ':':
                addToken(TokenType.COLON);
                break;
            case '.':
                addToken(TokenType.DOT);
                break;
            case '=':
                addToken(TokenType.EQUALS);
                break;
            case '"':
                string();
                break;
            case '-':
                if (match('>')) {
                    addToken(TokenType.ARROW);
                } else {
                    // bare '-' is now a token (used in weighted arrows
                    // like '-[0.5]->' and the closing '-' of '<-[0.5]-')
                    addToken(TokenType.DASH);
                }
                break;
            case '<':
                if (match('-')) {
                    addToken(TokenType.LARROW);
                } else {
                    System.err.println("Unexpected character: < at line " + line);
                }
                break;
            case '/':
                if (match('/')) {
                    // Line comment: consume up to (but not including) the
                    // newline. The '\n' case below handles the line counter.
                    while (peek() != '\n' && !isAtEnd()) advance();
                } else if (match('*')) {
                    blockComment();
                } else {
                    System.err.println("Unexpected character: / at line " + line);
                }
                break;
            case ' ':
            case '\r':
            case '\t':
                // Ignore whitespace
                break;
            case '\n':
                line++;
                break;
            default:
                if (isDigit(c)) {
                    number();
                } else if (isAlpha(c)) {
                    identifier();
                } else {
                    // Unknown character
                    System.err.println("Unexpected character: " + c + " at line " + line);
                }
                break;
        }
    }

    private void identifier() {
        while (isAlphaNumeric(peek()))
            advance();
        String text = source.substring(start, current);

        // Check if it's a keyword
        TokenType type = keywords.get(text);
        if (type == null)
            type = TokenType.IDENTIFIER;

        addToken(type, text);
    }

    private void number() {
        while (isDigit(peek()))
            advance();

        // Optional fractional part
        if (peek() == '.' && isDigit(peekNext())) {
            advance(); // consume '.'
            while (isDigit(peek()))
                advance();
        }

        double value = Double.parseDouble(source.substring(start, current));
        addToken(TokenType.NUMBER, value);
    }

    private void string() {
        while (peek() != '"' && !isAtEnd()) {
            if (peek() == '\n') line++;
            advance();
        }
        if (isAtEnd()) {
            System.err.println("Unterminated string starting at line " + line);
            return;
        }
        advance(); // consume closing "
        // Trim the surrounding quotes from the literal value.
        String value = source.substring(start + 1, current - 1);
        tokens.add(new Token(TokenType.STRING, source.substring(start, current), null, line));
        // We re-use the lexeme to carry the string content (with quotes);
        // callers use a helper to strip them or read source.substring directly.
        // Simpler approach: store the unquoted value in the lexeme by overwriting.
        Token t = tokens.remove(tokens.size() - 1);
        tokens.add(new Token(TokenType.STRING, value, null, t.line));
    }

    private void blockComment() {
        // Already past the opening "/*". Consume until we see "*/".
        // Tracks newlines so the line counter stays accurate.
        while (!isAtEnd()) {
            if (peek() == '*' && peekNext() == '/') {
                advance(); // consume '*'
                advance(); // consume '/'
                return;
            }
            if (peek() == '\n') line++;
            advance();
        }
        System.err.println("Unterminated block comment starting near line " + line);
    }

    private boolean match(char expected) {
        if (isAtEnd())
            return false;
        if (source.charAt(current) != expected)
            return false;
        current++;
        return true;
    }

    private char peek() {
        if (isAtEnd())
            return '\0';
        return source.charAt(current);
    }

    private char peekNext() {
        if (current + 1 >= source.length())
            return '\0';
        return source.charAt(current + 1);
    }

    private boolean isAlpha(char c) {
        return (c >= 'a' && c <= 'z') ||
                (c >= 'A' && c <= 'Z');
    }

    private boolean isAlphaNumeric(char c) {
        return isAlpha(c) || isDigit(c);
    }

    private boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private boolean isAtEnd() {
        return current >= source.length();
    }

    private char advance() {
        current++;
        return source.charAt(current - 1);
    }

    private void addToken(TokenType type) {
        addToken(type, null);
    }

    private void addToken(TokenType type, Object literal) {
        String text = source.substring(start, current);
        tokens.add(new Token(type, text, literal, line));
    }
}