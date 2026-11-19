import module java.base;

/// Recursive-descent parser: builds an expression tree from the lexer's tokens. Every value
/// is a list; a single number is a list of one, and arithmetic applies element by element.
///
/// Grammar, lowest to highest precedence:
///
///     list    := element (SEP element)* SEP?
///     element := expr ('..' expr)?
///     expr    := term (('+' | '-') term)*
///     term    := unary (('×' | '÷' | '%') unary | unary)*    -- adjacency is implicit ×
///     unary   := ('+' | '-') unary | power
///     power   := root ('²' | '³')* ('^' unary)?              -- ^ is right-associative
///     root    := '√' ('+' | '-')* root | primary
///     primary := number | 'π' | '(' list ')' | call
///     call    := FUNC '(' list ')' | FUNC list                -- without a bracket, takes the rest
final class Parser {

    enum Op { PLUS, MINUS, TIMES, DIVIDE, MOD, POWER }

    /// The expression tree. Sealed, so `evaluate` handles every node or doesn't compile.
    sealed interface Expr {
        record Literal(Value value) implements Expr { }
        record ListOf(List<Expr> elements) implements Expr { }
        record Range(Expr from, Expr to) implements Expr { }
        record Negate(Expr operand) implements Expr { }
        record Sqrt(Expr operand) implements Expr { }
        record Binary(Op op, Expr left, Expr right) implements Expr { }
        record Call(Func func, Expr args) implements Expr { }

        default List<Value> evaluate() {
            return switch (this) {
                case Literal(var value) -> List.of(value);
                case ListOf(var elements) -> elements.stream().flatMap(e -> e.evaluate().stream()).toList();
                case Range(var from, var to) -> range(from.evaluate(), to.evaluate());
                case Negate(var operand) -> operand.evaluate().stream().map(Value::negate).toList();
                case Sqrt(var operand) -> operand.evaluate().stream().map(Value::sqrt).toList();
                case Binary(var op, var left, var right) -> zip(op, left.evaluate(), right.evaluate());
                case Call(var func, var args) -> func.apply(args.evaluate());
            };
        }
    }

    /// Ranges stop well short of exhausting memory.
    static final int MAX_RANGE = 1_000_000;

    private final List<Lexer.Token> tokens;
    private final int end;
    private int next = 0;

    private Parser(List<Lexer.Token> tokens, int inputLength) {
        this.tokens = tokens;
        this.end = inputLength;
    }

    static Expr parse(List<Lexer.Token> tokens, int inputLength) {
        var parser = new Parser(tokens, inputLength);
        var expr = parser.list();
        if (parser.peek() instanceof Lexer.Token t) {
            throw parser.unexpected(t);
        }
        return expr;
    }

    // ---------------------------------------------------------------- grammar

    private Expr list() {
        var elements = new ArrayList<Expr>();
        if (atListEnd()) {
            return new Expr.ListOf(elements);
        }
        while (true) {
            elements.add(element());
            if (!(tok() instanceof Lexer.Tok.Sep)) {
                return elements.size() == 1 ? elements.getFirst() : new Expr.ListOf(elements);
            }
            next++;
            if (atListEnd()) {
                return new Expr.ListOf(elements); // trailing comma
            }
        }
    }

    private Expr element() {
        var from = expr();
        if (tok() instanceof Lexer.Tok.Range) {
            next++;
            return new Expr.Range(from, expr());
        }
        return from;
    }

    private Expr expr() {
        var left = term();
        while (tok() instanceof Lexer.Tok.Operator(var op) && (op == Op.PLUS || op == Op.MINUS)) {
            next++;
            left = new Expr.Binary(op, left, term());
        }
        return left;
    }

    private Expr term() {
        var left = unary();
        while (true) {
            switch (tok()) {
                case Lexer.Tok.Operator(var op) when op == Op.TIMES || op == Op.DIVIDE || op == Op.MOD -> {
                    next++;
                    left = new Expr.Binary(op, left, unary());
                }
                // Implicit multiplication: 2π, 3√2, 2{1+1}, {1+1}{2+2}, 2sum{1 2}
                case Lexer.Tok t when Lexer.startsOperand(t) -> left = new Expr.Binary(Op.TIMES, left, unary());
                case null, default -> {
                    return left;
                }
            }
        }
    }

    private Expr unary() {
        return switch (tok()) {
            case Lexer.Tok.Operator(var op) when op == Op.PLUS -> {
                next++;
                yield unary();
            }
            case Lexer.Tok.Operator(var op) when op == Op.MINUS -> {
                next++;
                yield new Expr.Negate(unary());
            }
            case null, default -> power();
        };
    }

    private Expr power() {
        var base = root();
        // ² and ³ bind like ^2 and ^3: -2² is -4, √4² is (√4)², 2²³ is (2²)³.
        while (tok() instanceof Lexer.Tok.Sup(var n)) {
            next++;
            base = new Expr.Binary(Op.POWER, base, new Expr.Literal(Value.of(n)));
        }
        if (tok() instanceof Lexer.Tok.Operator(var op) && op == Op.POWER) {
            next++;
            return new Expr.Binary(Op.POWER, base, unary());
        }
        return base;
    }

    private Expr root() {
        if (!(tok() instanceof Lexer.Tok.Sqrt)) {
            return primary();
        }
        next++;
        boolean negate = false;
        while (tok() instanceof Lexer.Tok.Operator(var op) && (op == Op.PLUS || op == Op.MINUS)) {
            negate ^= op == Op.MINUS;
            next++;
        }
        var operand = root();
        return new Expr.Sqrt(negate ? new Expr.Negate(operand) : operand);
    }

    private Expr primary() {
        if (!(peek() instanceof Lexer.Token token)) {
            throw new CalcError.Syntax("unexpected end of expression", end);
        }
        next++;
        return switch (token.tok()) {
            case Lexer.Tok.Num(var value) -> new Expr.Literal(value);
            case Lexer.Tok.Pi _ -> new Expr.Literal(new Value.Approx(Math.PI));
            case Lexer.Tok.Open(var close) -> {
                // Empty brackets only make sense as a function's list: sum{}.
                if (tok() instanceof Lexer.Tok.Close) {
                    throw unexpected(peek());
                }
                yield bracketed(close, token);
            }
            case Lexer.Tok.Fn(var func) -> {
                var args = switch (peek()) {
                    case Lexer.Token t when !t.spaceBefore() && t.tok() instanceof Lexer.Tok.Open(var close) -> {
                        next++;
                        yield bracketed(close, t);
                    }
                    case null, default -> {
                        if (atListEnd()) {
                            throw new CalcError.Syntax("'" + token.text() + "' needs numbers after it", token.position());
                        }
                        yield list();
                    }
                };
                yield new Expr.Call(func, args);
            }
            default -> throw unexpected(token);
        };
    }

    /// A list up to `close`; the opening bracket has been consumed.
    private Expr bracketed(char close, Lexer.Token opener) {
        var list = list();
        return switch (peek()) {
            case Lexer.Token t when t.tok() instanceof Lexer.Tok.Close(var c) && c == close -> {
                next++;
                yield list;
            }
            case Lexer.Token t -> throw new CalcError.Syntax(
                    "expected '%s' to match '%s' at position %d".formatted(close, opener.text(), opener.position() + 1),
                    t.position());
            case null -> throw new CalcError.Syntax("missing '%s' for '%s' at position %d"
                    .formatted(close, opener.text(), opener.position() + 1), end);
        };
    }

    // ---------------------------------------------------------------- helpers

    private Lexer.Token peek() {
        return next < tokens.size() ? tokens.get(next) : null;
    }

    private Lexer.Tok tok() {
        return next < tokens.size() ? tokens.get(next).tok() : null;
    }

    private boolean atListEnd() {
        return tok() == null || tok() instanceof Lexer.Tok.Close;
    }

    private CalcError.Syntax unexpected(Lexer.Token t) {
        return new CalcError.Syntax("unexpected '" + t.text() + "'", t.position());
    }

    // ---------------------------------------------------------------- evaluation

    static Value apply(Op op, Value a, Value b) {
        return switch (op) {
            case PLUS -> a.add(b);
            case MINUS -> a.subtract(b);
            case TIMES -> a.multiply(b);
            case DIVIDE -> a.divide(b);
            case MOD -> a.mod(b);
            case POWER -> a.power(b);
        };
    }

    /// Applies `op` element by element. A list of one pairs with every element of the other.
    static List<Value> zip(Op op, List<Value> a, List<Value> b) {
        if (a.size() == b.size()) {
            return IntStream.range(0, a.size()).mapToObj(i -> apply(op, a.get(i), b.get(i))).toList();
        }
        if (a.size() == 1) {
            return b.stream().map(y -> apply(op, a.getFirst(), y)).toList();
        }
        if (b.size() == 1) {
            return a.stream().map(x -> apply(op, x, b.getFirst())).toList();
        }
        throw new CalcError.Maths("can't combine lists of %d and %d numbers".formatted(a.size(), b.size()));
    }

    static List<Value> range(List<Value> from, List<Value> to) {
        if (from.size() != 1 || to.size() != 1) {
            throw new CalcError.Maths("a range needs a single number at each end");
        }
        var start = from.getFirst();
        var stop = to.getFirst();
        int direction = Value.compare(start, stop) <= 0 ? 1 : -1;
        var step = Value.of(direction);
        var out = new ArrayList<Value>();
        for (var v = start; Value.compare(v, stop) * direction <= 0; v = v.add(step)) {
            if (out.size() == MAX_RANGE) {
                throw new CalcError.Maths("range is longer than %,d numbers".formatted(MAX_RANGE));
            }
            out.add(v);
        }
        return out;
    }
}
