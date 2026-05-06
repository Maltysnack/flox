package flox;

enum TokenType {
    // Single-character tokens.
    SEMICOLON, LEFT_BRACKET, RIGHT_BRACKET, COMMA,
    LEFT_BRACE, RIGHT_BRACE, PERCENT,

    // One or two character tokens.
    ARROW,

    // Literals.
    IDENTIFIER, NUMBER,

    // Keywords.
    SYSTEM, MAP, LIST, PRINT, HELP,

    EOF
}