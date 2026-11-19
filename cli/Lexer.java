import module java.base;

/// Turns input text into tokens, then settles the two things that depend on context:
/// which spaces separate list elements, and where an unmatched closing bracket opens.
///
/// Positions are code-point indexes into the input, so `√`, `π` and `²` count as one.
final class Lexer {

    private Lexer() { }

    sealed interface Tok {
        record Num(Value value) implements Tok { }
        record Operator(Parser.Op op) implements Tok { }
        /// A superscript power: ² or ³.
        record Sup(int power) implements Tok { }
        record Sqrt() implements Tok { }
        record Pi() implements Tok { }
        /// An opening bracket, holding the character that closes it.
        record Open(char close) implements Tok { }
        record Close(char bracket) implements Tok { }
        /// A list separator: a comma, or a space between two operands.
        record Sep() implements Tok { }
        record Range() implements Tok { }
        record Fn(Func func) implements Tok { }
    }

    record Token(Tok tok, String text, int position, boolean spaceBefore) { }

    /// `decimalInput` records whether any number was written with a decimal point, because
    /// then results keep one too: `2.5x2` is `5.0`.
    record Lexed(List<Token> tokens, boolean decimalInput) { }

    /// Words other than function names.
    private static final Map<String, Tok> WORDS = Map.of(
            "sqrt", new Tok.Sqrt(), "mod", new Tok.Operator(Parser.Op.MOD), "pi", new Tok.Pi());

    /// Characters that group digits: `1_000`, `1'000`, `1’000`, and the no-break spaces.
    private static boolean isDigitGrouping(int c) {
        return c == '_' || c == '\'' || c == '’' || c == ' ' || c == ' ' || c == ' ';
    }

    private static boolean isDigit(int[] cs, int i) {
        return i < cs.length && cs[i] >= '0' && cs[i] <= '9';
    }

    static Lexed lex(String input) {
        var lexed = tokenize(input);
        var tokens = new ArrayList<>(lexed.tokens());
        insertSeparators(tokens);
        inferOpeningBrackets(tokens);
        return new Lexed(tokens, lexed.decimalInput());
    }

    private static Lexed tokenize(String input) {
        int[] cs = input.codePoints().toArray();
        var tokens = new ArrayList<Token>();
        boolean decimalInput = false;
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
            if (isDigit(cs, i) || (c == '.' && isDigit(cs, i + 1))) {
                var number = scanNumber(cs, i);
                decimalInput |= number.decimal();
                tok = new Tok.Num(number.value());
                length = number.length();
            } else if (c == '…') {
                tok = new Tok.Range();
            } else if (c == '.' && i + 1 < cs.length && cs[i + 1] == '.') {
                // `1...10` too, so a third dot isn't read as the start of `.10`.
                tok = new Tok.Range();
                length = i + 2 < cs.length && cs[i + 2] == '.' ? 3 : 2;
            } else if (c == ',') {
                checkComma(cs, i, spaceBefore);
                tok = new Tok.Sep();
            } else if ((c == 'x' || c == 'X') && i + 1 < cs.length && (cs[i + 1] == '̄' || cs[i + 1] == '̅')) {
                // x̄ is x plus a combining macron (or overline); there's no single character for it.
                tok = new Tok.Fn(Func.MEAN);
                length = 2;
            } else if (word != null) {
                tok = word.getValue();
                length = word.getKey().length();
            } else if (symbol.isPresent()) {
                tok = new Tok.Fn(symbol.get());
            } else {
                tok = switch (c) {
                    case '+' -> new Tok.Operator(Parser.Op.PLUS);
                    case '-', '−' -> new Tok.Operator(Parser.Op.MINUS);
                    // Middle dot, dot operator, bullet operator and bullet all read as multiplication.
                    case '*', 'x', 'X', '×', '·', '⋅', '∙', '•' -> new Tok.Operator(Parser.Op.TIMES);
                    case '/', '÷' -> new Tok.Operator(Parser.Op.DIVIDE);
                    case '%' -> new Tok.Operator(Parser.Op.MOD);
                    case '^' -> new Tok.Operator(Parser.Op.POWER);
                    case '²' -> new Tok.Sup(2);
                    case '³' -> new Tok.Sup(3);
                    case '√' -> new Tok.Sqrt();
                    case 'π' -> new Tok.Pi();
                    case '(' -> new Tok.Open(')');
                    case '{' -> new Tok.Open('}');
                    case '[' -> new Tok.Open(']');
                    case ')', '}', ']' -> new Tok.Close((char) c);
                    case '@' -> throw new CalcError.Syntax("'@' reads stdin, which this version doesn't support yet", i);
                    default -> throw new CalcError.Syntax("unexpected character '" + Character.toString(c) + "'", i);
                };
            }
            tokens.add(new Token(tok, new String(cs, i, length), i, spaceBefore));
            spaceBefore = false;
            i += length;
        }
        return new Lexed(tokens, decimalInput);
    }

    /// The longest word (function name or keyword) starting at `i`, ignoring case.
    private static Map.Entry<String, Tok> wordAt(int[] cs, int i) {
        return Stream.concat(
                        Func.NAMES.entrySet().stream().map(e -> Map.entry(e.getKey(), (Tok) new Tok.Fn(e.getValue()))),
                        WORDS.entrySet().stream())
                .filter(e -> matchesAt(cs, i, e.getKey()))
                .max(Comparator.comparingInt(e -> e.getKey().length()))
                .orElse(null);
    }

    private static boolean matchesAt(int[] cs, int i, String word) {
        if (i + word.length() > cs.length) {
            return false;
        }
        for (int k = 0; k < word.length(); k++) {
            if (Character.toLowerCase(cs[i + k]) != word.charAt(k)) {
                return false;
            }
        }
        return true;
    }

    private record Scanned(Value value, int length, boolean decimal) { }

    private static Scanned scanNumber(int[] cs, int start) {
        var plain = new StringBuilder();
        boolean decimal = false;
        int j = start;
        while (j < cs.length) {
            int c = cs[j];
            boolean betweenDigits = j > start && isDigit(cs, j - 1) && isDigit(cs, j + 1);
            if (c >= '0' && c <= '9') {
                plain.append((char) c);
            } else if (c == '.' && j + 1 < cs.length && cs[j + 1] == '.') {
                break; // a range: 1..10
            } else if (c == '.') {
                plain.append('.');
                decimal = true;
            } else if (!(betweenDigits && isDigitGrouping(c))) {
                break;
            }
            j++;
        }
        var literal = new String(cs, start, j - start);
        var parts = plain.toString().split("\\.", -1);
        if (parts.length > 2) {
            throw new CalcError.Syntax("invalid number '" + literal + "'", start);
        }
        var fraction = parts.length == 2 ? parts[1] : "";
        // 12.345 is exactly 12345/1000.
        var value = new Value.Exact(new BigInteger(parts[0] + fraction), BigInteger.TEN.pow(fraction.length()));
        return new Scanned(value, j - start, decimal);
    }

    /// Rejects a comma that is probably digit grouping rather than a list separator:
    /// `1,234` is ambiguous, while `1, 234` and `1,23` are lists.
    private static void checkComma(int[] cs, int i, boolean spaceBefore) {
        int before = 0, after = 0;
        while (i - before - 1 >= 0 && isDigit(cs, i - before - 1)) {
            before++;
        }
        while (isDigit(cs, i + after + 1)) {
            after++;
        }
        if (spaceBefore || before == 0 || after != 3) {
            return;
        }
        var left = new String(cs, i - before, before);
        var right = new String(cs, i + 1, after);
        throw new CalcError.Syntax("'%s,%s' is ambiguous: write %s%s or %s_%s, or '%s, %s' for a list"
                .formatted(left, right, left, right, left, right, left, right), i - before);
    }

    static boolean endsOperand(Tok tok) {
        return switch (tok) {
            case Tok.Num _, Tok.Pi _, Tok.Close _, Tok.Sup _ -> true;
            default -> false;
        };
    }

    static boolean startsOperand(Tok tok) {
        return switch (tok) {
            case Tok.Num _, Tok.Pi _, Tok.Open _, Tok.Sqrt _, Tok.Fn _ -> true;
            default -> false;
        };
    }

    /// Turns a space into a list separator where it sits between two operands (`1 2`), or
    /// before a sign attached to the next operand (`1 -2`, but not `1 - 2`).
    private static void insertSeparators(List<Token> tokens) {
        var at = new ArrayList<Integer>();
        for (int i = 1; i < tokens.size(); i++) {
            var prev = tokens.get(i - 1);
            var token = tokens.get(i);
            if (!token.spaceBefore() || !endsOperand(prev.tok())) {
                continue;
            }
            boolean signed = token.tok() instanceof Tok.Operator(var op)
                    && (op == Parser.Op.PLUS || op == Parser.Op.MINUS)
                    && i + 1 < tokens.size()
                    && !tokens.get(i + 1).spaceBefore()
                    && startsOperand(tokens.get(i + 1).tok());
            if (!startsOperand(token.tok()) && !signed) {
                continue;
            }
            // "1 000" is digit grouping in some styles; don't let it quietly become 1, 0.
            if (prev.tok() instanceof Tok.Num && token.tok() instanceof Tok.Num
                    && token.text().matches("0\\d\\d")) {
                throw new CalcError.Syntax("'%s %s' looks like digit grouping: write %s%s or %s_%s (spaces separate list elements)"
                        .formatted(prev.text(), token.text(), prev.text(), token.text(), prev.text(), token.text()),
                        prev.position());
            }
            at.add(i);
        }
        for (int k = at.size() - 1; k >= 0; k--) {
            var next = tokens.get(at.get(k));
            tokens.add(at.get(k), new Token(new Tok.Sep(), " ", next.position(), true));
        }
    }

    /// Adds an opening bracket at the start for each closing bracket that has none, so
    /// `2+4+8}/2` means `{2+4+8}/2`. Later closers get outer brackets: `1+2)x3}` becomes
    /// `{(1+2)x3}`. A closer that mismatches an open bracket is left for the parser to report.
    private static void inferOpeningBrackets(List<Token> tokens) {
        var open = new ArrayDeque<Character>();
        var inferred = new ArrayList<Token>();
        scan:
        for (var token : tokens) {
            switch (token.tok()) {
                case Tok.Open(var close) -> open.push(close);
                case Tok.Close _ when open.isEmpty() -> inferred.add(token);
                case Tok.Close(var c) when open.peek() == c -> open.pop();
                case Tok.Close _ -> { break scan; }
                default -> { }
            }
        }
        // The last unmatched closer is outermost, so its opener goes first.
        for (var closer : inferred) {
            char c = ((Tok.Close) closer.tok()).bracket();
            var opener = switch (c) {
                case ')' -> "(";
                case '}' -> "{";
                default -> "[";
            };
            tokens.addFirst(new Token(new Tok.Open(c), opener, closer.position(), false));
        }
    }
}
