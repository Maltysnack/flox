package flox;

import java.util.ArrayList;
import java.util.List;

import static flox.TokenType.*;

class Parser {
    private static class ParseError extends RuntimeException {
    }

    private final List<Token> tokens;
    private int current = 0;

    Parser(List<Token> tokens) {
        this.tokens = tokens;
    }

    List<Ast> parse() {
        List<Ast> statements = new ArrayList<>();
        while (!isAtEnd()) {
            statements.add(declaration());
        }
        return statements;
    }

    private Ast declaration() {
        try {
            if (match(SYSTEM))
                return systemDeclaration();
            if (match(MAP))
                return mapStatement();
            if (match(LIST))
                return listStatement();
            if (match(PRINT))
                return printStatement();
            if (match(HELP))
                return helpStatement();

            return riverChain();
        } catch (ParseError error) {
            synchronize();
            return null;
        }
    }

    // system Canberra;
    private Ast systemDeclaration() {
        Token name = consume(IDENTIFIER, "Expect system name.");
        consume(SEMICOLON, "Expect ';' after system name.");
        return new Ast.SystemDecl(name);
    }

    // map Canberra;
    private Ast mapStatement() {
        Token name = consume(IDENTIFIER, "Expect system name.");
        consume(SEMICOLON, "Expect ';' after system name.");
        return new Ast.MapStmt(name);
    }

    // list Canberra;
    private Ast listStatement() {
        Token name = consume(IDENTIFIER, "Expect system name.");
        consume(SEMICOLON, "Expect ';' after system name.");
        return new Ast.ListStmt(name);
    }

    // print Canberra; or print Canberra 5;
    private Ast printStatement() {
        Token name = consume(IDENTIFIER, "Expect system name.");
        Double rainfall = null;
        if (match(NUMBER)) {
            rainfall = (Double) previous().literal;
        }
        consume(SEMICOLON, "Expect ';' after print statement.");
        return new Ast.PrintStmt(name, rainfall);
    }

    // help;
    private Ast helpStatement() {
        consume(SEMICOLON, "Expect ';' after 'help'.");
        return new Ast.HelpStmt();
    }

    // Googong[2, 2.0] -> dam1{...} -> Queanbeyan[3, 1.5];
    private Ast riverChain() {
        Ast expr = riverOrDam();

        while (match(ARROW)) {
            Token operator = previous();
            Ast right = riverOrDam();
            expr = new Ast.Binary(expr, operator, right);
        }

        consume(SEMICOLON, "Expect ';' after river chain.");
        return expr;
    }

    private Ast riverOrDam() {
        if (check(IDENTIFIER)) {
            Token name = advance();

            // Check if it's a dam (ends with "Dam")
            if (name.lexeme.endsWith("Dam")) {
                return dam(name);
            } else {
                return river(name);
            }
        }
        throw error(peek(), "Expect river or dam name.");
    }

    // Googong[2, 2.0] or Googong[2, 2.0, 0.6] or Googong[]
    private Ast river(Token name) {
        consume(LEFT_BRACKET, "Expect '[' after river name.");

        // Check for empty terminator []
        if (check(RIGHT_BRACKET)) {
            consume(RIGHT_BRACKET, "Expect ']'.");
            return new Ast.River(name, 0, 0, 0.7); // Terminator
        }

        double baseFlow = consumeNumber("Expect base flow.");
        consume(COMMA, "Expect ',' after base flow.");
        double peakMultiplier = consumeNumber("Expect peak multiplier.");

        double decayRate = 0.7; // default
        if (match(COMMA)) {
            decayRate = consumeNumber("Expect decay rate.");
        }

        consume(RIGHT_BRACKET, "Expect ']' after river parameters.");
        return new Ast.River(name, baseFlow, peakMultiplier, decayRate);
    }

    // googongDam{1000, 0.8, 1.2} or googongDam{1000, 70%, 0.8, 1.2}
    private Ast dam(Token name) {
        consume(LEFT_BRACE, "Expect '{' after dam name.");

        double capacity = consumeNumber("Expect capacity.");
        consume(COMMA, "Expect ',' after capacity.");

        // Check if next is a threshold with %
        double threshold = 0.8; // default 80%
        double normalRate, openRate;

        if (checkAhead(NUMBER, PERCENT)) {
            // Format: {1000, 70%, 0.8, 1.2}
            threshold = consumeNumber("Expect threshold.") / 100.0;
            consume(PERCENT, "Expect '%' after threshold.");
            consume(COMMA, "Expect ',' after threshold.");
            normalRate = consumeNumber("Expect normal rate.");
            consume(COMMA, "Expect ',' after normal rate.");
            openRate = consumeNumber("Expect open rate.");
        } else {
            // Format: {1000, 0.8, 1.2} - no threshold specified
            normalRate = consumeNumber("Expect normal rate.");
            consume(COMMA, "Expect ',' after normal rate.");
            openRate = consumeNumber("Expect open rate.");
        }

        consume(RIGHT_BRACE, "Expect '}' after dam parameters.");
        return new Ast.Dam(name, capacity, threshold, normalRate, openRate);
    }

    private double consumeNumber(String message) {
        if (!check(NUMBER)) {
            throw error(peek(), message);
        }
        return (Double) advance().literal;
    }

    private boolean checkAhead(TokenType first, TokenType second) {
        if (isAtEnd())
            return false;
        if (tokens.get(current).type != first)
            return false;
        if (current + 1 >= tokens.size())
            return false;
        return tokens.get(current + 1).type == second;
    }

    private boolean match(TokenType... types) {
        for (TokenType type : types) {
            if (check(type)) {
                advance();
                return true;
            }
        }
        return false;
    }

    private Token consume(TokenType type, String message) {
        if (check(type))
            return advance();
        throw error(peek(), message);
    }

    private boolean check(TokenType type) {
        if (isAtEnd())
            return false;
        return peek().type == type;
    }

    private Token advance() {
        if (!isAtEnd())
            current++;
        return previous();
    }

    private boolean isAtEnd() {
        return peek().type == EOF;
    }

    private Token peek() {
        return tokens.get(current);
    }

    private Token previous() {
        return tokens.get(current - 1);
    }

    private ParseError error(Token token, String message) {
        Flox.error(token, message);
        return new ParseError();
    }

    private void synchronize() {
        advance();
        while (!isAtEnd()) {
            if (previous().type == SEMICOLON)
                return;
            switch (peek().type) {
                case SYSTEM:
                case MAP:
                case PRINT:
                    return;
            }
            advance();
        }
    }
}