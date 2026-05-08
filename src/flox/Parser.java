package flox;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
            Ast stmt = declaration();
            if (stmt != null) statements.add(stmt);
        }
        return statements;
    }

    private Ast declaration() {
        try {
            if (match(SYSTEM))   return systemDeclaration();
            if (match(RIVER))    return riverDeclaration();
            if (match(DAM))      return damDeclaration();
            if (match(OUTLET))   return outletDeclaration();
            if (match(MAP))     return mapStatement();
            if (match(LIST))     return listStatement();
            if (match(PRINT))    return printStatement();
            if (match(HELP))     return helpStatement();
            return chain();
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

    // print Canberra;
    // print Canberra 5;            (single rainfall event of 5mm on day 1)
    // print Canberra [0, 5, 30, 10];  (per-day rainfall series in mm)
    private Ast printStatement() {
        Token name = consume(IDENTIFIER, "Expect system name.");
        double[] rainfall = null;
        if (match(NUMBER)) {
            rainfall = new double[] { (Double) previous().literal };
        } else if (match(LEFT_BRACKET)) {
            List<Double> values = new ArrayList<>();
            if (!check(RIGHT_BRACKET)) {
                values.add(consumeNumber("Expect number in rainfall series."));
                while (match(COMMA)) {
                    values.add(consumeNumber("Expect number after ',' in rainfall series."));
                }
            }
            consume(RIGHT_BRACKET, "Expect ']' to close rainfall series.");
            rainfall = new double[values.size()];
            for (int i = 0; i < values.size(); i++) rainfall[i] = values.get(i);
        }
        consume(SEMICOLON, "Expect ';' after print statement.");
        return new Ast.PrintStmt(name, rainfall);
    }

    // help;
    private Ast helpStatement() {
        consume(SEMICOLON, "Expect ';' after 'help'.");
        return new Ast.HelpStmt();
    }

    // river Spring { base: 2, peak: 2.0, decay: 0.7 }
    private Ast riverDeclaration() {
        Token name = consume(IDENTIFIER, "Expect river name.");
        Map<String, Double> attrs = parseBlock(name);
        consumeOptional(SEMICOLON);

        validateAttrs(name, attrs,
                new String[] { "base", "peak" },
                new String[] { "decay" });

        double base  = attrs.get("base");
        double peak  = attrs.get("peak");
        double decay = attrs.getOrDefault("decay", 0.7);
        return new Ast.River(name, base, peak, decay);
    }

    // dam googongDam { capacity: 1000, threshold: 80%, normalRate: 0.8, openRate: 1.2 }
    private Ast damDeclaration() {
        Token name = consume(IDENTIFIER, "Expect dam name.");
        Map<String, Double> attrs = parseBlock(name);
        consumeOptional(SEMICOLON);

        validateAttrs(name, attrs,
                new String[] { "capacity", "threshold", "normalRate", "openRate" },
                new String[] {});

        double capacity   = attrs.get("capacity");
        double threshold  = attrs.get("threshold");
        double normalRate = attrs.get("normalRate");
        double openRate   = attrs.get("openRate");
        return new Ast.Dam(name, capacity, threshold, normalRate, openRate);
    }

    // outlet lowerMongolo;   or   outlet lowerMongolo {}
    private Ast outletDeclaration() {
        Token name = consume(IDENTIFIER, "Expect outlet name.");
        if (check(LEFT_BRACE)) {
            Map<String, Double> attrs = parseBlock(name);
            if (!attrs.isEmpty()) {
                throw error(name, "Outlet '" + name.lexeme + "' takes no attributes.");
            }
        }
        consumeOptional(SEMICOLON);
        return new Ast.Outlet(name);
    }

    // A chain is one or more bare identifiers joined by ->.
    // A -> B -> C;
    private Ast chain() {
        if (!check(IDENTIFIER)) {
            throw error(peek(), "Expect declaration or chain.");
        }
        List<Token> nodes = new ArrayList<>();
        nodes.add(advance());
        while (match(ARROW)) {
            nodes.add(consume(IDENTIFIER, "Expect node name after '->'."));
        }
        consume(SEMICOLON, "Expect ';' after chain.");
        return new Ast.Chain(nodes);
    }

    // { key: value (, key: value)* }   ,/whitespace flexible, trailing comma allowed
    // Threshold values may have a trailing % which divides by 100.
    private Map<String, Double> parseBlock(Token declName) {
        consume(LEFT_BRACE, "Expect '{' to open '" + declName.lexeme + "' attributes.");
        Map<String, Double> attrs = new HashMap<>();
        while (!check(RIGHT_BRACE) && !isAtEnd()) {
            Token key = consume(IDENTIFIER, "Expect attribute name.");
            consume(COLON, "Expect ':' after attribute name '" + key.lexeme + "'.");
            double value = consumeNumber("Expect number after ':'.");
            if (match(PERCENT)) value = value / 100.0;
            if (attrs.containsKey(key.lexeme)) {
                throw error(key, "Duplicate attribute '" + key.lexeme + "'.");
            }
            attrs.put(key.lexeme, value);
            // Comma is optional between attributes; whitespace also fine.
            match(COMMA);
        }
        consume(RIGHT_BRACE, "Expect '}' to close attributes.");
        return attrs;
    }

    private void validateAttrs(Token declName, Map<String, Double> attrs,
                               String[] required, String[] optional) {
        // Every key must be in required or optional.
        outer: for (String key : attrs.keySet()) {
            for (String r : required) if (key.equals(r)) continue outer;
            for (String o : optional) if (key.equals(o)) continue outer;
            throw error(declName, "Unknown attribute '" + key + "' on '" + declName.lexeme + "'.");
        }
        // Every required key must be present.
        for (String r : required) {
            if (!attrs.containsKey(r)) {
                throw error(declName,
                        "Missing required attribute '" + r + "' on '" + declName.lexeme + "'.");
            }
        }
    }

    private double consumeNumber(String message) {
        if (!check(NUMBER)) {
            throw error(peek(), message);
        }
        return (Double) advance().literal;
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

    private void consumeOptional(TokenType type) {
        if (check(type)) advance();
    }

    private Token consume(TokenType type, String message) {
        if (check(type)) return advance();
        throw error(peek(), message);
    }

    private boolean check(TokenType type) {
        if (isAtEnd()) return false;
        return peek().type == type;
    }

    private Token advance() {
        if (!isAtEnd()) current++;
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
            if (previous().type == SEMICOLON) return;
            switch (peek().type) {
                case SYSTEM:
                case RIVER:
                case DAM:
                case OUTLET:
                case MAP:
                case LIST:
                case PRINT:
                    return;
                default:
                    break;
            }
            advance();
        }
    }
}
