import module java.base;

/// Parses tokens into a syntax tree of `Node`s, which is checked once and can then be
/// evaluated any number of times (once per line of stdin, when streaming).
///
/// Grammar, lowest to highest precedence:
///
///     list    := element (SEP element)* SEP?
///     element := expr ('..' expr)?
///     expr    := term (('+' | '-') term)*
///     term    := unary (('×' | '÷' | '%') unary | unary)*    -- adjacency is implicit ×
///     unary   := ('+' | '-') unary | power
///     power   := root (SUP | POSTFIX)* ('^' unary)?          -- ^ is right-associative
///     root    := '√' ('+' | '-')* root | primary
///     primary := atom POSTFIX*                               -- POSTFIX is '!' or '°'; they bind tightest
///     atom    := number | 'π' | '@' | '(' list ')' | call
///     call    := FUNC '(' list ')' | FUNC list                -- without a bracket, takes the rest
///
/// A closing bracket with no opening bracket implies one at the very start of the
/// expression: `2+4+8}/2` is `{2+4+8}/2`.
final class Parser {

    private final List<Lexer.Token> tokens;
    private final int[] cs;
    private int next = 0;

    private Parser(List<Lexer.Token> tokens, int[] cs) {
        this.tokens = tokens;
        this.cs = cs;
    }

    /// A tokenised expression, which may still be missing stdin.
    static final class Expression {
        private final String source;
        private final List<Lexer.Token> tokens;

        private Expression(String source, List<Lexer.Token> tokens) {
            this.source = source;
            this.tokens = tokens;
        }

        static Expression parse(String input, Lexer.Syntax syntax) {
            return new Expression(input, Lexer.tokenize(input, syntax));
        }

        boolean isEmpty() {
            return tokens.isEmpty();
        }

        /// True when the expression uses `@` explicitly.
        boolean readsStdin() {
            return tokens.stream().anyMatch(t -> t.tok() instanceof Lexer.Tok.Stdin);
        }

        /// True when the expression starts with an operator, so it continues a value read
        /// from stdin. A leading '-' only counts on a lone number: `calc 2x5 | calc -1` is 9,
        /// but `-1x2` is complete, so a negative number in a larger expression never waits on stdin.
        boolean isContinuation() {
            return isLoneNegative() || (!tokens.isEmpty() && switch (tokens.getFirst().tok()) {
                case Lexer.Tok.Operator(var op) -> op != Node.Op.MINUS;
                case Lexer.Tok.Sup _, Lexer.Tok.Bang _, Lexer.Tok.Degree _ -> true;
                default -> false;
            });
        }

        /// True when the whole expression is one negative number: `-1` or `- 1`.
        boolean isLoneNegative() {
            return tokens.size() == 2
                    && tokens.get(0).tok().isOperator(Node.Op.MINUS)
                    && tokens.get(1).tok() instanceof Lexer.Tok.Num;
        }

        /// True when the expression ends with an operator or function, so stdin completes it.
        boolean awaitsOperand() {
            return !tokens.isEmpty() && switch (tokens.getLast().tok()) {
                case Lexer.Tok.Operator _, Lexer.Tok.Sqrt _, Lexer.Tok.Fn _, Lexer.Tok.Range _ -> true;
                default -> false;
            };
        }

        /// Places stdin (`@`) wherever the expression lacks an operand, unless it already says where.
        void feedStdin() {
            if (readsStdin()) {
                return;
            }
            int end = source.codePointCount(0, source.length());
            if (isEmpty() || awaitsOperand()) {
                tokens.add(Lexer.Token.synthetic(new Lexer.Tok.Stdin(), end));
            }
            if (isContinuation()) {
                tokens.addFirst(Lexer.Token.synthetic(new Lexer.Tok.Stdin(), 0));
            }
        }

        Program compile() {
            inferOpeningBrackets(tokens);
            var parser = new Parser(tokens, source.codePoints().toArray());
            var root = parser.list();
            if (parser.peek() instanceof Lexer.Token t) {
                throw t.tok() instanceof Lexer.Tok.Close(var c)
                        ? new CalcError.Input("unmatched '" + c + "'").at(t.start(), t.end())
                        : parser.unexpected(t);
            }
            return new Program(source, root);
        }
    }

    /// A parsed expression, ready to evaluate.
    record Program(String source, Node root) {
        /// True when stdin can be processed a line at a time with the same result as reading
        /// it all as one list.
        boolean streams() {
            return root.isElementwise();
        }

        /// Evaluates with `stdin` (null when there is none) for any `@`.
        List<Value> evaluate(List<Value> stdin) {
            return root.eval(stdin);
        }
    }

    /// Evaluates a complete expression that can't read stdin: a line of input.
    static List<Value> evaluate(String input, Lexer.Syntax syntax) {
        var expression = Expression.parse(input, syntax);
        if (expression.isEmpty()) {
            throw new CalcError.Input("empty expression");
        }
        for (var t : expression.tokens) {
            if (t.tok() instanceof Lexer.Tok.Stdin) {
                throw new CalcError.Input("'@' can only be used in arguments").at(t.start(), t.end());
            }
        }
        return expression.compile().evaluate(null);
    }

    /// Adds an opening bracket at the start for each closing bracket that has none.
    ///
    /// An unmatched closer can only occur when every bracket before it is balanced, so the
    /// inferred opener always pairs with exactly that closer. Later closers get outer
    /// brackets: `1+2)x3}` becomes `{(1+2)x3}`. A closer that mismatches an open bracket
    /// (`{1+2)`) is left for the parser to report.
    private static void inferOpeningBrackets(List<Lexer.Token> tokens) {
        var open = new ArrayDeque<Character>();
        var inferred = new ArrayList<Character>();
        scan:
        for (var token : tokens) {
            switch (token.tok()) {
                case Lexer.Tok.Open(var close) -> open.push(close);
                case Lexer.Tok.Close(var c) when open.isEmpty() -> inferred.add(c);
                case Lexer.Tok.Close(var c) when open.peek() == c -> open.pop();
                case Lexer.Tok.Close _ -> { break scan; }
                default -> { }
            }
        }
        // The last unmatched closer is outermost, so its opener goes first.
        for (var c : inferred) {
            tokens.addFirst(Lexer.Token.synthetic(new Lexer.Tok.Open(c), 0));
        }
    }

    // ---------------------------------------------------------------- grammar

    private Node list() {
        int start = here();
        var items = new ArrayList<Node>();
        if (!atListEnd()) {
            while (true) {
                items.add(element());
                if (!(tok() instanceof Lexer.Tok.Sep)) {
                    break;
                }
                next++;
                if (atListEnd()) {
                    break; // trailing comma
                }
            }
        }
        if (items.size() == 1) {
            return items.getFirst();
        }
        return new Node(new Node.Expr.ListOf(items), start, items.isEmpty() ? start : items.getLast().end());
    }

    private Node element() {
        var from = expr();
        if (!(tok() instanceof Lexer.Tok.Range)) {
            return from;
        }
        next++;
        return Node.range(from, expr());
    }

    private Node expr() {
        var node = term();
        while (tok() instanceof Lexer.Tok.Operator(var op) && (op == Node.Op.PLUS || op == Node.Op.MINUS)) {
            next++;
            node = Node.binary(op, node, term());
        }
        return node;
    }

    private Node term() {
        var node = unary();
        while (true) {
            switch (tok()) {
                case Lexer.Tok.Operator(var op) when op == Node.Op.TIMES || op == Node.Op.DIVIDE || op == Node.Op.MOD -> {
                    next++;
                    node = Node.binary(op, node, unary());
                }
                // Implicit multiplication: 2π, 3√2, 2{1+1}, {1+1}{2+2}, 2sum{1 2}
                case Lexer.Tok t when t.startsOperand() -> node = Node.binary(Node.Op.TIMES, node, unary());
                case null, default -> {
                    return node;
                }
            }
        }
    }

    private Node unary() {
        var t = peek();
        if (t != null && t.tok().isOperator(Node.Op.PLUS)) {
            next++;
            return unary();
        }
        if (t != null && t.tok().isOperator(Node.Op.MINUS)) {
            next++;
            var operand = unary();
            return new Node(new Node.Expr.Neg(operand), t.start(), operand.end());
        }
        return power();
    }

    private Node power() {
        var base = root();
        // ² and ³ bind like ^2 and ^3: -2² is -4, √4² is (√4)², 2²³ is (2²)³.
        // A factorial or degree sign can follow them too: 2²! is (2²)!.
        while (peek() instanceof Lexer.Token t
                && (t.tok() instanceof Lexer.Tok.Sup || t.tok() instanceof Lexer.Tok.Bang || t.tok() instanceof Lexer.Tok.Degree)) {
            next++;
            base = t.tok() instanceof Lexer.Tok.Sup(var n)
                    ? Node.binary(Node.Op.POWER, base, new Node(new Node.Expr.Num(Value.of(n)), t.start(), t.end()))
                    : postfix(base, t);
        }
        if (tok() instanceof Lexer.Tok.Operator(var op) && op == Node.Op.POWER) {
            next++;
            return Node.binary(Node.Op.POWER, base, unary());
        }
        return base;
    }

    private Node root() {
        var sqrt = peek();
        if (sqrt == null || !(sqrt.tok() instanceof Lexer.Tok.Sqrt)) {
            return primary();
        }
        next++;
        boolean negate = false;
        while (tok() instanceof Lexer.Tok.Operator(var op) && (op == Node.Op.PLUS || op == Node.Op.MINUS)) {
            negate ^= op == Node.Op.MINUS;
            next++;
        }
        var operand = root();
        if (negate) {
            operand = new Node(new Node.Expr.Neg(operand), operand.start(), operand.end());
        }
        return new Node(new Node.Expr.Sqrt(operand), sqrt.start(), operand.end());
    }

    /// An atom and any `!` or `°` after it, so `√4!` is √(4!) and `-30°` is -(30°).
    private Node primary() {
        var node = atom();
        while (peek() instanceof Lexer.Token t && (t.tok() instanceof Lexer.Tok.Bang || t.tok() instanceof Lexer.Tok.Degree)) {
            next++;
            node = postfix(node, t);
        }
        return node;
    }

    private Node atom() {
        if (!(peek() instanceof Lexer.Token t)) {
            int end = here();
            throw new CalcError.Input("unexpected end of expression").at(end, end);
        }
        next++;
        return switch (t.tok()) {
            case Lexer.Tok.Num(var value) -> new Node(new Node.Expr.Num(value), t.start(), t.end());
            case Lexer.Tok.Pi _ -> new Node(new Node.Expr.Num(new Value.Approx(Math.PI)), t.start(), t.end());
            case Lexer.Tok.Stdin _ -> new Node(new Node.Expr.Stdin(), t.start(), t.end());
            case Lexer.Tok.Open(var close) -> {
                // Empty brackets only make sense as a function's list: sum{}.
                if (tok() instanceof Lexer.Tok.Close) {
                    throw unexpected(peek());
                }
                yield bracketed(t, close);
            }
            case Lexer.Tok.Fn(var func) -> {
                Node args;
                if (peek() instanceof Lexer.Token open && !open.spaceBefore() && open.tok() instanceof Lexer.Tok.Open(var close)) {
                    next++;
                    args = bracketed(open, close);
                } else if (atListEnd()) {
                    throw new CalcError.Input("'" + Lexer.text(cs, t) + "' needs numbers after it").at(t.start(), t.end());
                } else {
                    args = list();
                }
                yield new Node(new Node.Expr.Call(func, args), t.start(), args.end());
            }
            default -> throw unexpected(t);
        };
    }

    /// A list up to `close`; the opening bracket `open` has been consumed.
    private Node bracketed(Lexer.Token open, char close) {
        var inner = list();
        var t = peek();
        if (t == null) {
            throw new CalcError.Input("missing '" + close + "'").at(open.start(), open.end());
        }
        if (t.tok() instanceof Lexer.Tok.Close(var c) && c == close) {
            next++;
            return new Node(inner.expr(), open.start(), t.end());
        }
        throw new CalcError.Input("expected '%s' to match the bracket at position %d".formatted(close, open.start() + 1))
                .at(t.start(), t.end());
    }

    /// Applies a postfix `!` (factorial) or `°` (degrees to radians) to `operand`.
    private static Node postfix(Node operand, Lexer.Token op) {
        if (op.tok() instanceof Lexer.Tok.Degree) {
            var perDegree = new Node(new Node.Expr.Num(new Value.Approx(Math.PI / 180)), op.start(), op.end());
            return new Node(new Node.Expr.Binary(Node.Op.TIMES, operand, perDegree), operand.start(), op.end());
        }
        return new Node(new Node.Expr.Call(Func.FACTORIAL, operand), operand.start(), op.end());
    }

    // ---------------------------------------------------------------- helpers

    private Lexer.Token peek() {
        return next < tokens.size() ? tokens.get(next) : null;
    }

    private Lexer.Tok tok() {
        return next < tokens.size() ? tokens.get(next).tok() : null;
    }

    /// Where the next token starts, or the end of the input.
    private int here() {
        if (next < tokens.size()) {
            return tokens.get(next).start();
        }
        return tokens.isEmpty() ? 0 : tokens.getLast().end();
    }

    private boolean atListEnd() {
        return tok() == null || tok() instanceof Lexer.Tok.Close;
    }

    private CalcError unexpected(Lexer.Token t) {
        return new CalcError.Input("unexpected '" + Lexer.text(cs, t) + "'").at(t.start(), t.end());
    }
}
