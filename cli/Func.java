import module java.base;

/// Named functions. Each takes one list (a single number is a list of one). Aggregates
/// combine or reorder the list; the others apply to each element.
enum Func {
    SUM, PRODUCT, MEAN, MEDIAN, MODE, MIN, MAX, STDEV,
    ABS, FLOOR, CEIL, ROUND, LN, LOG, EXP,
    SIN, COS, TAN, ASIN, ACOS, ATAN, SINH, COSH, TANH, FACTORIAL;

    enum Kind { AGGREGATE, ELEMENTWISE }

    /// Spellings, matched case-insensitively. The longest match wins, so `product` isn't
    /// `prod` × ..., and `sinh` isn't `sin` × ...
    static final Map<String, Func> NAMES = Map.ofEntries(
            Map.entry("sum", SUM),
            Map.entry("product", PRODUCT), Map.entry("prod", PRODUCT),
            Map.entry("mean", MEAN), Map.entry("avg", MEAN), Map.entry("average", MEAN),
            Map.entry("median", MEDIAN), Map.entry("mode", MODE),
            Map.entry("min", MIN), Map.entry("max", MAX),
            Map.entry("stdev", STDEV),
            Map.entry("abs", ABS), Map.entry("floor", FLOOR), Map.entry("ceil", CEIL), Map.entry("round", ROUND),
            Map.entry("ln", LN), Map.entry("log", LOG), Map.entry("exp", EXP),
            Map.entry("sin", SIN), Map.entry("cos", COS), Map.entry("tan", TAN),
            Map.entry("asin", ASIN), Map.entry("acos", ACOS), Map.entry("atan", ATAN),
            Map.entry("sinh", SINH), Map.entry("cosh", COSH), Map.entry("tanh", TANH),
            Map.entry("factorial", FACTORIAL), Map.entry("fact", FACTORIAL));

    /// The function whose name starts `word` (already in lower case), with that name.
    static Optional<Map.Entry<String, Func>> prefixOf(String word) {
        return NAMES.entrySet().stream()
                .filter(e -> word.startsWith(e.getKey()))
                .max(Comparator.comparingInt(e -> e.getKey().length()));
    }

    /// Single-character spellings: ∑ (macOS Option-W), Greek Σ, ∏ (Option-Shift-P), Greek Π.
    static Optional<Func> symbol(int codePoint) {
        return switch (codePoint) {
            case '∑', 'Σ' -> Optional.of(SUM);
            case '∏', 'Π' -> Optional.of(PRODUCT);
            default -> Optional.empty();
        };
    }

    Kind kind() {
        return switch (this) {
            case SUM, PRODUCT, MEAN, MEDIAN, MODE, MIN, MAX, STDEV -> Kind.AGGREGATE;
            case ABS, FLOOR, CEIL, ROUND, LN, LOG, EXP, SIN, COS, TAN, ASIN, ACOS, ATAN, SINH, COSH, TANH,
                 FACTORIAL -> Kind.ELEMENTWISE;
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
            case MODE -> mode(sorted(args, "mode"));
            case MIN -> List.of(sorted(args, "min").getFirst());
            case MAX -> List.of(sorted(args, "max").getLast());
            case STDEV -> {
                // Sample standard deviation (n − 1), like a spreadsheet's STDEV.
                if (args.size() < 2) {
                    throw new CalcError.Input("stdev needs at least two numbers");
                }
                var m = mean(args);
                var squares = args.stream().map(v -> v.subtract(m).power(Value.of(2))).toList();
                yield List.of(sum(squares).divide(Value.of(args.size() - 1)).sqrt());
            }
            case ABS -> each(args, Value::abs);
            case FLOOR -> each(args, Value::floor);
            case CEIL -> each(args, Value::ceil);
            case ROUND -> each(args, Value::round);
            case FACTORIAL -> each(args, Value::factorial);
            // The JDK has no BigDecimal logarithms or trigonometry, so these use doubles.
            case LN -> real(args, Math::log);
            case LOG -> real(args, Math::log10);
            case EXP -> real(args, Math::exp);
            case SIN -> real(args, Math::sin);
            case COS -> real(args, Math::cos);
            case TAN -> real(args, Math::tan);
            case ASIN -> real(args, Math::asin);
            case ACOS -> real(args, Math::acos);
            case ATAN -> real(args, Math::atan);
            case SINH -> real(args, Math::sinh);
            case COSH -> real(args, Math::cosh);
            case TANH -> real(args, Math::tanh);
        };
    }

    private static Value sum(List<Value> args) {
        return args.stream().reduce(Value.of(0), Value::add);
    }

    private static Value mean(List<Value> args) {
        if (args.isEmpty()) {
            throw new CalcError.Input("mean of an empty list");
        }
        return sum(args).divide(Value.of(args.size()));
    }

    /// The most frequent value, or every value that ties for most frequent, in ascending
    /// order. Equal values are found exactly, so 0.5 and 1/2 count as the same.
    private static List<Value> mode(List<Value> sorted) {
        var runs = new ArrayList<List<Value>>();
        for (var v : sorted) {
            if (runs.isEmpty() || Value.compare(runs.getLast().getFirst(), v) != 0) {
                runs.add(new ArrayList<>());
            }
            runs.getLast().add(v);
        }
        int most = runs.stream().mapToInt(List::size).max().orElseThrow();
        return runs.stream().filter(run -> run.size() == most).map(List::getFirst).toList();
    }

    private static List<Value> sorted(List<Value> args, String name) {
        if (args.isEmpty()) {
            throw new CalcError.Input(name + " of an empty list");
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
