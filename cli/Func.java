import module java.base;

/// Named functions. Each takes one list (a single number is a list of one). Aggregates
/// reduce the list to one number; the others apply to each element.
enum Func {
    SUM, PRODUCT, MEAN, MEDIAN, MIN, MAX, STDEV,
    ABS, FLOOR, CEIL, ROUND, LN, LOG, EXP, SIN, COS, TAN;

    /// Spellings, matched case-insensitively. The lexer takes the longest match, so `max`
    /// is never read as `ma`.
    static final Map<String, Func> NAMES = Map.ofEntries(
            Map.entry("sum", SUM),
            Map.entry("product", PRODUCT), Map.entry("prod", PRODUCT),
            Map.entry("mean", MEAN), Map.entry("avg", MEAN), Map.entry("average", MEAN),
            Map.entry("median", MEDIAN),
            Map.entry("min", MIN), Map.entry("max", MAX),
            Map.entry("stdev", STDEV),
            Map.entry("abs", ABS), Map.entry("floor", FLOOR), Map.entry("ceil", CEIL), Map.entry("round", ROUND),
            Map.entry("ln", LN), Map.entry("log", LOG), Map.entry("exp", EXP),
            Map.entry("sin", SIN), Map.entry("cos", COS), Map.entry("tan", TAN));

    /// Single-character spellings: ∑ (macOS Option-W), Greek Σ, ∏ (Option-Shift-P), Greek Π.
    static Optional<Func> symbol(int codePoint) {
        return switch (codePoint) {
            case '∑', 'Σ' -> Optional.of(SUM);
            case '∏', 'Π' -> Optional.of(PRODUCT);
            default -> Optional.empty();
        };
    }

    List<Value> apply(List<Value> args) {
        return switch (this) {
            case SUM -> List.of(sum(args));
            case PRODUCT -> List.of(args.stream().reduce(Value.of(1), Value::multiply));
            case MEAN -> List.of(mean(args));
            case MEDIAN -> {
                var sorted = sorted(args, "median");
                int n = sorted.size();
                yield List.of(n % 2 == 1
                        ? sorted.get(n / 2)
                        : sorted.get(n / 2 - 1).add(sorted.get(n / 2)).divide(Value.of(2)));
            }
            case MIN -> List.of(sorted(args, "min").getFirst());
            case MAX -> List.of(sorted(args, "max").getLast());
            case STDEV -> {
                // Sample standard deviation (n − 1), like a spreadsheet's STDEV.
                if (args.size() < 2) {
                    throw new CalcError.Maths("stdev needs at least two numbers");
                }
                var m = mean(args);
                var squares = args.stream().map(v -> v.subtract(m).power(Value.of(2))).toList();
                yield List.of(sum(squares).divide(Value.of(args.size() - 1)).sqrt());
            }
            case ABS -> each(args, Value::abs);
            case FLOOR -> each(args, Value::floor);
            case CEIL -> each(args, Value::ceil);
            case ROUND -> each(args, Value::round);
            // The JDK has no BigDecimal logarithms or trigonometry, so these use doubles.
            case LN -> real(args, Math::log);
            case LOG -> real(args, Math::log10);
            case EXP -> real(args, Math::exp);
            case SIN -> real(args, Math::sin);
            case COS -> real(args, Math::cos);
            case TAN -> real(args, Math::tan);
        };
    }

    private static Value sum(List<Value> args) {
        return args.stream().reduce(Value.of(0), Value::add);
    }

    private static Value mean(List<Value> args) {
        if (args.isEmpty()) {
            throw new CalcError.Maths("mean of an empty list");
        }
        return sum(args).divide(Value.of(args.size()));
    }

    private static List<Value> sorted(List<Value> args, String name) {
        if (args.isEmpty()) {
            throw new CalcError.Maths(name + " of an empty list");
        }
        return args.stream().sorted(Value::compare).toList();
    }

    private static List<Value> each(List<Value> args, UnaryOperator<Value> f) {
        return args.stream().map(f).toList();
    }

    private static List<Value> real(List<Value> args, DoubleUnaryOperator f) {
        return args.stream().map(v -> Value.approx(f.applyAsDouble(v.toDouble()))).toList();
    }
}
