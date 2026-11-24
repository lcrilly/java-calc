import module java.base;

/// Splits input into tokens and works out which spaces separate list elements.
///
/// A space separates list elements only between two complete operands (`1 2`, `2 π`), or
/// before a sign attached to an operand (`1 -2`); elsewhere it is ignored (`1 + 2`).
/// Positions are code-point offsets, so `√`, `π` and `²` count as one character each.
final class Lexer {

    private Lexer() { }

    /// How numbers in the input are written. `pretty` (from --pretty) reads numbers in a
    /// local style, with its decimal mark and digit grouping; null means plain input.
    record Syntax(NumberStyle pretty) {
        static final Syntax PLAIN = new Syntax(null);

        char decimalMark() {
            return pretty == null ? '.' : pretty.decimal();
        }

        /// The user's own decimal mark. Where it is ',', plain input like `3,5` is ambiguous.
        /// Only looked up when a comma between digits makes it matter.
        char localDecimal() {
            return pretty != null ? pretty.decimal() : NumberStyle.detected().decimal();
        }
    }

    sealed interface Tok {
        record Num(Value value) implements Tok { }
        record Operator(Node.Op op) implements Tok { }
        /// A superscript power: ² or ³.
        record Sup(int power) implements Tok { }
        /// Postfix factorial: `5!`.
        record Bang() implements Tok { }
        /// Postfix degrees, converting to radians: `30°`.
        record Degree() implements Tok { }
        record Sqrt() implements Tok { }
        record Pi() implements Tok { }
        /// `@`: stdin.
        record Stdin() implements Tok { }
        /// An opening bracket, holding the character that closes it.
        record Open(char close) implements Tok { }
        record Close(char bracket) implements Tok { }
        /// A list separator: a comma, or a space between two operands.
        record Sep() implements Tok { }
        record Range() implements Tok { }
        record Fn(Func func) implements Tok { }

        default boolean startsOperand() {
            return switch (this) {
                case Num _, Pi _, Open _, Sqrt _, Fn _, Stdin _ -> true;
                default -> false;
            };
        }

        default boolean endsOperand() {
            return switch (this) {
                case Num _, Pi _, Close _, Stdin _, Sup _, Bang _, Degree _ -> true;
                default -> false;
            };
        }

        default boolean isOperator(Node.Op... ops) {
            return this instanceof Operator(var op) && Arrays.asList(ops).contains(op);
        }
    }

    /// A token and where it is in the input: code points [start, end).
    record Token(Tok tok, int start, int end, boolean spaceBefore) {
        /// A token that isn't in the input, such as an implied bracket or stdin.
        static Token synthetic(Tok tok, int at) {
            return new Token(tok, at, at, false);
        }
    }

    /// The text of a token, from the input's code points.
    static String text(int[] cs, Token t) {
        return new String(cs, t.start(), t.end() - t.start());
    }

    /// Words other than function names.
    private static final Map<String, Tok> WORDS = Map.of(
            "sqrt", new Tok.Sqrt(), "mod", new Tok.Operator(Node.Op.MOD), "pi", new Tok.Pi());

    /// Characters that group digits in any mode: `1_000`, `1'000`, `1’000`, and no-break spaces.
    private static boolean isDigitGrouping(int c) {
        return c == '_' || c == '\'' || c == '’' || c == ' ' || c == ' ' || c == ' ';
    }

    private static boolean isDigit(int[] cs, int i) {
        return i >= 0 && i < cs.length && cs[i] >= '0' && cs[i] <= '9';
    }

    private static int at(int[] cs, int i) {
        return i < cs.length ? cs[i] : -1;
    }

    static List<Token> tokenize(String input, Syntax syntax) {
        int[] cs = input.codePoints().toArray();
        char mark = syntax.decimalMark();
        var tokens = new ArrayList<Token>();
        boolean spaceBefore = false;
        int i = 0;
        while (i < cs.length) {
            int c = cs[i];
            if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                spaceBefore = true;
                i++;
                continue;
            }
            var word = Character.isLetter(c) ? wordAt(cs, i) : null;
            var symbol = Func.symbol(c);
            Tok tok;
            int length = 1;
            if (isDigit(cs, i) || (c == '.' && mark == '.' && isDigit(cs, i + 1))) {
                var number = scanNumber(cs, i, syntax);
                tok = new Tok.Num(number.value());
                length = number.length();
            } else if (c == '…') {
                tok = new Tok.Range();
            } else if (c == '.' && at(cs, i + 1) == '.') {
                // `1...10` too, so a third dot isn't read as the start of `.10`.
                tok = new Tok.Range();
                length = at(cs, i + 2) == '.' ? 3 : 2;
            } else if (c == '!' && at(cs, i + 1) == '!') {
                // n!! could mean (n!)! or the double factorial, and an interactive shell
                // replaces an unquoted !! with the previous command anyway.
                throw new CalcError.Input("'!!' isn't supported: write {n!}! for the factorial of a factorial")
                        .at(i, i + 2);
            } else if (c == ',') {
                checkComma(cs, i, spaceBefore, syntax);
                tok = new Tok.Sep();
            } else if ((c == 'x' || c == 'X') && (at(cs, i + 1) == '̄' || at(cs, i + 1) == '̅')) {
                // x̄ is x plus a combining macron (or overline); there's no single character for it.
                tok = new Tok.Fn(Func.MEAN);
                length = 2;
            } else if (word != null) {
                tok = word.getValue();
                length = word.getKey().length();
            } else if (symbol.isPresent()) {
                tok = new Tok.Fn(symbol.get());
            } else {
                int here = i;
                tok = switch (c) {
                    case '+' -> new Tok.Operator(Node.Op.PLUS);
                    case '-', '−' -> new Tok.Operator(Node.Op.MINUS);
                    // Middle dot, dot operator, bullet operator and bullet all read as multiplication.
                    case '*', 'x', 'X', '×', '·', '⋅', '∙', '•' -> new Tok.Operator(Node.Op.TIMES);
                    case '/', '÷' -> new Tok.Operator(Node.Op.DIVIDE);
                    case '%' -> new Tok.Operator(Node.Op.MOD);
                    case '^' -> new Tok.Operator(Node.Op.POWER);
                    case '!' -> new Tok.Bang();
                    // The degree sign, and the ordinal indicator that looks just like it (Option-0 on a Mac).
                    case '°', 'º' -> new Tok.Degree();
                    case '²' -> new Tok.Sup(2);
                    case '³' -> new Tok.Sup(3);
                    case '√' -> new Tok.Sqrt();
                    case 'π' -> new Tok.Pi();
                    case '@' -> new Tok.Stdin();
                    case '(' -> new Tok.Open(')');
                    case '{' -> new Tok.Open('}');
                    case '[' -> new Tok.Open(']');
                    case ')', '}', ']' -> new Tok.Close((char) c);
                    default -> throw new CalcError.Input("unexpected character '" + Character.toString(c) + "'")
                            .at(here, here + 1);
                };
            }
            tokens.add(new Token(tok, i, i + length, spaceBefore));
            spaceBefore = false;
            i += length;
        }
        insertSeparators(tokens, cs);
        return tokens;
    }

    /// The function name or word operator at `i`: the longest one that starts the run of
    /// letters there, ignoring case. Its key is the name as matched.
    private static Map.Entry<String, Tok> wordAt(int[] cs, int i) {
        var run = new StringBuilder();
        for (int j = i; j < cs.length && Character.isLetter(cs[j]); j++) {
            run.appendCodePoint(cs[j] < 128 ? Character.toLowerCase(cs[j]) : cs[j]);
        }
        var word = run.toString();
        var function = Func.prefixOf(word).map(e -> Map.entry(e.getKey(), (Tok) new Tok.Fn(e.getValue())));
        var other = WORDS.entrySet().stream().filter(e -> word.startsWith(e.getKey())).findFirst();
        return Stream.concat(function.stream(), other.stream())
                .max(Comparator.comparingInt(e -> e.getKey().length()))
                .orElse(null);
    }

    private record Scanned(Value value, int length) { }

    private static Scanned scanNumber(int[] cs, int start, Syntax syntax) {
        char mark = syntax.decimalMark();
        var style = syntax.pretty();
        var plain = new StringBuilder();
        boolean seenDecimal = false;
        int j = start;
        while (j < cs.length) {
            int c = cs[j];
            boolean betweenDigits = j > start && isDigit(cs, j - 1) && isDigit(cs, j + 1);
            if (c >= '0' && c <= '9') {
                plain.append((char) c);
            } else if (c == '.' && at(cs, j + 1) == '.') {
                break; // a range: 1..10
            } else if (c == mark) {
                plain.append('.');
                seenDecimal = true;
            } else if (betweenDigits && isDigitGrouping(c)) {
                // skip: 1_000
            } else if (betweenDigits && !seenDecimal && style != null && style.group() != null
                    && style.group() == c && style.isGroupSize(digitsFrom(cs, j + 1))) {
                // skip: a local group separator between correctly sized groups (--pretty)
            } else {
                break;
            }
            j++;
        }
        var parts = plain.toString().split("\\.", -1);
        if (parts.length > 2) {
            throw new CalcError.Input("invalid number '" + new String(cs, start, j - start) + "'").at(start, j);
        }
        var fraction = parts.length == 2 ? parts[1] : "";
        // 12.345 is exactly 12345/1000.
        var value = new Value.Exact(new BigInteger(parts[0] + fraction), BigInteger.TEN.pow(fraction.length()));
        return new Scanned(value, j - start);
    }

    private static int digitsFrom(int[] cs, int j) {
        int n = 0;
        while (isDigit(cs, j + n)) {
            n++;
        }
        return n;
    }

    /// Rejects a comma that probably isn't a list separator: `1,234` (grouping) and, where
    /// the user writes decimals with a comma, `3,5`. `1, 234` is always a list.
    private static void checkComma(int[] cs, int i, boolean spaceBefore, Syntax syntax) {
        int before = 0;
        while (isDigit(cs, i - before - 1)) {
            before++;
        }
        int after = digitsFrom(cs, i + 1);
        if (spaceBefore || before == 0 || after == 0 || syntax.pretty() != null) {
            return;
        }
        var left = new String(cs, i - before, before);
        var right = new String(cs, i + 1, after);
        if (after == 3) {
            throw new CalcError.Input(("'%s,%s' is ambiguous: write %s%s or %s_%s, use '%s, %s' for a list, "
                    + "or --pretty to read grouped numbers").formatted(left, right, left, right, left, right, left, right))
                    .at(i - before, i + 1 + after);
        }
        if (syntax.localDecimal() == ',') {
            throw new CalcError.Input(("'%s,%s' is ambiguous: calc reads '.' as the decimal mark, so write %s.%s, "
                    + "use '%s, %s' for a list, or --pretty to read local numbers").formatted(left, right, left, right, left, right))
                    .at(i - before, i + 1 + after);
        }
    }

    /// Turns a space into a list separator where it sits between two operands (`1 2`), or
    /// before a sign attached to the next operand (`1 -2`, but not `1 - 2`).
    private static void insertSeparators(List<Token> tokens, int[] cs) {
        var at = new ArrayList<Integer>();
        for (int i = 1; i < tokens.size(); i++) {
            var prev = tokens.get(i - 1);
            var token = tokens.get(i);
            if (!token.spaceBefore() || !prev.tok().endsOperand()) {
                continue;
            }
            boolean signed = token.tok().isOperator(Node.Op.PLUS, Node.Op.MINUS)
                    && i + 1 < tokens.size()
                    && !tokens.get(i + 1).spaceBefore()
                    && tokens.get(i + 1).tok().startsOperand();
            if (!token.tok().startsOperand() && !signed) {
                continue;
            }
            // "1 000" was digit grouping before spaces made lists; don't let it quietly become 1, 0.
            if (prev.tok() instanceof Tok.Num && token.tok() instanceof Tok.Num) {
                var left = text(cs, prev);
                var right = text(cs, token);
                if (right.matches("0\\d\\d")) {
                    throw new CalcError.Input("'%s %s' looks like digit grouping: write %s%s or %s_%s (spaces separate list elements)"
                            .formatted(left, right, left, right, left, right))
                            .at(prev.start(), token.end());
                }
            }
            at.add(i);
        }
        for (int k = at.size() - 1; k >= 0; k--) {
            int start = tokens.get(at.get(k)).start();
            tokens.add(at.get(k), new Token(new Tok.Sep(), start - 1, start, true));
        }
    }
}
