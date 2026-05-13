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
            if (match(MODE))     return modeDeclaration();
            if (match(SYSTEM))   return systemDeclaration();
            if (match(RIVER))    return riverDeclaration();
            if (match(DAM))      return damDeclaration();
            if (match(OUTLET))   return outletDeclaration();
            if (match(NODE))     return nodeDeclaration();
            if (match(START))    return startStatement();
            if (match(DISABLE))  return disableStatement();
            if (match(COMPARE))  return compareStatement();
            if (match(MAP))      return mapStatement();
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

    // mode river;   or   mode markov;
    private Ast modeDeclaration() {
        Token name = consume(IDENTIFIER, "Expect mode name (e.g. river, markov).");
        consume(SEMICOLON, "Expect ';' after mode name.");
        return new Ast.ModeDecl(name);
    }

    // compare SystemName [ticks] as
    //   "label",
    //   "label" with disable Plant,
    //   "label" with Backup.supply = 80 with disable A -> B;
    private Ast compareStatement() {
        Token name = consume(IDENTIFIER, "Expect system name after 'compare'.");
        Double ticks = null;
        if (match(NUMBER)) {
            ticks = (Double) previous().literal;
        }
        consume(AS, "Expect 'as' before the scenario list.");

        List<Ast.Scenario> scenarios = new ArrayList<>();
        scenarios.add(scenario());
        while (match(COMMA)) {
            scenarios.add(scenario());
        }
        consume(SEMICOLON, "Expect ';' after compare statement.");
        return new Ast.CompareStmt(name, ticks, scenarios);
    }

    private Ast.Scenario scenario() {
        Token label = consume(STRING, "Expect scenario label string.");
        List<Ast.OverrideSpec> overrides = new ArrayList<>();
        while (match(WITH)) {
            overrides.add(overrideClause());
        }
        return new Ast.Scenario(label.lexeme, overrides);
    }

    // Parse one override clause body (the part after 'with'):
    //   disable Name
    //   disable A -> B   /   disable A <- B
    //   Name.attribute = N   (currently only 'supply' supported)
    private Ast.OverrideSpec overrideClause() {
        if (match(DISABLE)) {
            Token first = consume(IDENTIFIER, "Expect node name after 'disable'.");
            if (match(ARROW)) {
                Token second = consume(IDENTIFIER, "Expect node name after '->'.");
                return Ast.OverrideSpec.disableEdge(first, second, true);
            } else if (match(LARROW)) {
                Token second = consume(IDENTIFIER, "Expect node name after '<-'.");
                return Ast.OverrideSpec.disableEdge(first, second, false);
            }
            return Ast.OverrideSpec.disableNode(first);
        }
        // Otherwise property override: Name.attr = N
        Token name = consume(IDENTIFIER, "Expect node name or 'disable' in override clause.");
        consume(DOT, "Expect '.' after node name in property override.");
        Token attr = consume(IDENTIFIER, "Expect attribute name.");
        if (!attr.lexeme.equals("supply")) {
            throw error(attr, "Only 'supply' is supported as a property override (got '" + attr.lexeme + "').");
        }
        consume(EQUALS, "Expect '=' after attribute name in override.");
        boolean negate = match(DASH);
        double value = consumeNumber("Expect number on right-hand side of override.");
        if (negate) value = -value;
        return Ast.OverrideSpec.supply(name, value);
    }

    // disable Name;
    // disable A -> B;
    // disable A <- B;
    private Ast disableStatement() {
        Token first = consume(IDENTIFIER, "Expect node name after 'disable'.");
        Token second = null;
        boolean forward = true;
        if (match(ARROW)) {
            forward = true;
            second = consume(IDENTIFIER, "Expect node name after '->' in 'disable'.");
        } else if (match(LARROW)) {
            forward = false;
            second = consume(IDENTIFIER, "Expect node name after '<-' in 'disable'.");
        }
        consume(SEMICOLON, "Expect ';' after disable statement.");
        return new Ast.DisableStmt(first, second, forward);
    }

    // start NodeName 1.0;
    private Ast startStatement() {
        Token node = consume(IDENTIFIER, "Expect node name after 'start'.");
        double mass = consumeNumber("Expect initial mass after node name in 'start'.");
        consume(SEMICOLON, "Expect ';' after start statement.");
        return new Ast.StartStmt(node, mass);
    }

    // river Spring { base: 2, peak: 2.0, decay: 0.7 }
    // 'supply' is an optional grid-mode attribute (default 0).
    private Ast riverDeclaration() {
        Token name = consume(IDENTIFIER, "Expect river name.");
        Map<String, Double> attrs = parseBlock(name);
        consumeOptional(SEMICOLON);

        validateAttrs(name, attrs,
                new String[] { "base", "peak" },
                new String[] { "decay", "supply" });

        double base   = attrs.get("base");
        double peak   = attrs.get("peak");
        double decay  = attrs.getOrDefault("decay", 0.7);
        double supply = attrs.getOrDefault("supply", 0.0);
        return new Ast.River(name, base, peak, decay, supply);
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

    // node Name;             (no attributes)
    // node Name { supply: -40 }   (markov / grid; supply optional)
    private Ast nodeDeclaration() {
        Token name = consume(IDENTIFIER, "Expect node name.");
        Map<String, Double> attrs;
        if (check(LEFT_BRACE)) {
            attrs = parseBlock(name);
        } else {
            attrs = new HashMap<>();
        }
        consumeOptional(SEMICOLON);

        validateAttrs(name, attrs,
                new String[] {},
                new String[] { "supply" });

        double supply = attrs.getOrDefault("supply", 0.0);
        return new Ast.NodeDecl(name, supply);
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

    // A chain is one or more bare identifiers joined by arrows.
    // The four arrow forms:
    //   ->        forward, default weight 1
    //   <-        backward, default weight 1
    //   -[w]->    forward, weight w
    //   <-[w]-    backward, weight w
    private Ast chain() {
        if (!check(IDENTIFIER)) {
            throw error(peek(), "Expect declaration or chain.");
        }
        List<Token> nodes = new ArrayList<>();
        List<Ast.ChainEdge> arrows = new ArrayList<>();
        nodes.add(advance());

        while (atArrowStart()) {
            Ast.ChainEdge arrow = parseArrow();
            arrows.add(arrow);
            nodes.add(consume(IDENTIFIER, "Expect node name after arrow."));
        }
        consume(SEMICOLON, "Expect ';' after chain.");
        return new Ast.Chain(nodes, arrows);
    }

    // The four arrow forms each start in a recognisable way:
    //   ARROW      ->        forward, no weight
    //   LARROW     <-        backward, no weight (or start of <-[w]-)
    //   DASH '['   -[w]->    forward, with weight
    private boolean atArrowStart() {
        if (check(ARROW)) return true;
        if (check(LARROW)) return true;
        if (check(DASH) && peekIs(1, LEFT_BRACKET)) return true;
        return false;
    }

    private Ast.ChainEdge parseArrow() {
        if (match(ARROW)) {
            return new Ast.ChainEdge(true, null, previous());
        }
        if (match(LARROW)) {
            // either bare <-, or <-[w]-
            Token op = previous();
            if (match(LEFT_BRACKET)) {
                double w = consumeNumber("Expect weight number inside arrow brackets.");
                consume(RIGHT_BRACKET, "Expect ']' after arrow weight.");
                consume(DASH, "Expect '-' to close '<-[w]-' arrow.");
                return new Ast.ChainEdge(false, w, op);
            }
            return new Ast.ChainEdge(false, null, op);
        }
        // Forward weighted arrow: -[w]->
        Token op = consume(DASH, "Expect arrow.");
        consume(LEFT_BRACKET, "Expect '[' after '-' to start a weighted arrow.");
        double w = consumeNumber("Expect weight number inside arrow brackets.");
        consume(RIGHT_BRACKET, "Expect ']' after arrow weight.");
        consume(ARROW, "Expect '->' to close '-[w]->' arrow.");
        return new Ast.ChainEdge(true, w, op);
    }

    private boolean peekIs(int offset, TokenType type) {
        int idx = current + offset;
        if (idx >= tokens.size()) return false;
        return tokens.get(idx).type == type;
    }

    // { key: value (, key: value)* }   ,/whitespace flexible, trailing comma allowed
    // Threshold values may have a trailing % which divides by 100.
    private Map<String, Double> parseBlock(Token declName) {
        consume(LEFT_BRACE, "Expect '{' to open '" + declName.lexeme + "' attributes.");
        Map<String, Double> attrs = new HashMap<>();
        while (!check(RIGHT_BRACE) && !isAtEnd()) {
            Token key = consume(IDENTIFIER, "Expect attribute name.");
            consume(COLON, "Expect ':' after attribute name '" + key.lexeme + "'.");
            // Optional unary minus on numeric values (e.g. supply: -40).
            boolean negate = match(DASH);
            double value = consumeNumber("Expect number after ':'.");
            if (negate) value = -value;
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
                case MODE:
                case SYSTEM:
                case RIVER:
                case DAM:
                case OUTLET:
                case NODE:
                case START:
                case DISABLE:
                case COMPARE:
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
