package flox;

enum TokenType {
    // Single-character tokens.
    SEMICOLON, LEFT_BRACKET, RIGHT_BRACKET, COMMA,
    LEFT_BRACE, RIGHT_BRACE, PERCENT, COLON, DASH,

    // One or two character tokens.
    ARROW, LARROW,

    // Literals.
    IDENTIFIER, NUMBER,

    // Keywords.
    SYSTEM, MAP, LIST, PRINT, HELP,
    RIVER, DAM, OUTLET,
    MODE, START,

    EOF
}
