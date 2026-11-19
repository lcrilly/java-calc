/// Command-line calculator, run straight from source with no build step:
///
///     java Calc.java 2x{3+4}
///     alias jcalc='java /path/to/cli/Calc.java'
///
/// This compact source file (JDK 25: no class declaration, java.base imported automatically)
/// handles options and output. The other .java files in this directory hold the calculator
/// itself; the launcher compiles them on demand (multi-file source programs, JDK 22+):
///
///     Lexer   text → tokens; decides which spaces separate list elements
///     Parser  tokens → a sealed tree of records, evaluated with a pattern-matching switch
///     Value   exact BigInteger fractions, falling back to double once irrational
///     Func    sum, avg, stdev, round, ln, sin, ...
///     Output  result formatting rules
///
/// Exit codes: 0 success, 1 maths error, 2 usage or syntax error.

static final String VERSION = "0.2.0";

static final String USAGE = """
        Usage: java Calc.java [OPTIONS] EXPRESSION...

        Operators:  +  -  x * × · •  ÷ /  % mod  ^  ² ³
        Brackets:   {} or () or []
        Symbols:    π pi   √ sqrt
        Lists:      1 2 3  or  1,2,3
        Ranges:     1..10  or  1…10
        Functions:  sum ∑  product prod ∏  avg mean average x̄  median  min  max
                    stdev  abs  floor  ceil  round  ln  log  exp  sin  cos  tan

        Arithmetic applies element by element: {1 2 3}x2 is 2 4 6. Lists print one
        number per line. Adjacent terms multiply: 2π, 3√2, 2{1+1}. Group digits with _
        (1_000). An unmatched closing bracket wraps everything before it: 3+4+5}/2 → 6.

        Shell-safe spellings avoid quoting: x for *, {} for (). Quote anything else
        your shell treats specially.

        Options (before the expression; -- ends them):
          -h, --help                Show this help
          -p, --precision=N|max     Decimal places for non-terminating results
                                    (default 5; max = full double precision)
          -V, --version             Show version

        Exit codes: 0 success, 1 maths error, 2 usage or syntax error.""";

sealed interface Command {
    record Help() implements Command { }
    record Version() implements Command { }
    record Run(Output.Precision precision, List<String> expression) implements Command { }
}

void main(String[] args) {
    System.exit(run(List.of(args)));
}

int run(List<String> args) {
    Command command;
    try {
        command = parseOptions(args);
    } catch (IllegalArgumentException e) {
        return fail(2, e.getMessage());
    }
    return switch (command) {
        case Command.Help _ -> {
            IO.println(USAGE);
            yield 0;
        }
        case Command.Version _ -> {
            IO.println("calc (Java) " + VERSION);
            yield 0;
        }
        case Command.Run(var precision, var expression) -> calculate(precision, expression);
    };
}

int calculate(Output.Precision precision, List<String> args) {
    if (args.isEmpty()) {
        System.err.println(USAGE);
        return 2;
    }
    if (args.stream().anyMatch(a -> a.indexOf('�') >= 0)) {
        return fail(2, "the arguments contain characters that couldn't be decoded. Use a UTF-8 locale, "
                + "or the ASCII spellings: sqrt, pi, x, ^2");
    }
    var braceError = braceExpansion(args);
    if (braceError != null) {
        return fail(2, braceError);
    }
    // Arguments are joined with spaces, which separate list elements: `calc 1 2 3`.
    var input = String.join(" ", args);
    try {
        var lexed = Lexer.lex(input);
        var values = Parser.parse(lexed.tokens(), input.codePointCount(0, input.length())).evaluate();
        for (var value : values) {
            IO.println(Output.format(value, lexed.decimalInput(), precision));
        }
        return 0;
    } catch (CalcError error) {
        return switch (error) {
            case CalcError.Syntax e -> {
                // Draw a caret under the problem. Columns count code points, so √ and π are one each.
                System.err.printf("calc: %s%n  %s%n  %s^%n", e.getMessage(), input, " ".repeat(e.position));
                yield 2;
            }
            case CalcError.Maths e -> fail(1, e.getMessage());
        };
    }
}

int fail(int status, String message) {
    System.err.println("calc: " + message);
    return status;
}

// ---------------------------------------------------------------- options

/// Splits leading options from the expression. Options stop at the first argument that
/// isn't one (so `-2` and `-pi` are expressions), or at `--`.
Command parseOptions(List<String> args) {
    Output.Precision precision = new Output.Precision.Default();
    int i = 0;
    while (i < args.size()) {
        var arg = args.get(i);
        switch (arg) {
            case "--" -> {
                return new Command.Run(precision, args.subList(i + 1, args.size()));
            }
            case "-h", "--help" -> {
                return new Command.Help();
            }
            case "-V", "--version" -> {
                return new Command.Version();
            }
            case "-r", "--rows" -> throw new IllegalArgumentException("'" + arg + "' needs stdin, which this version doesn't support yet");
            case String s when s.equals("--pretty") || s.startsWith("--pretty=") ->
                    throw new IllegalArgumentException("'--pretty' isn't supported in this version");
            case String s when s.equals("--precision") || s.equals("-p") -> {
                if (i + 1 >= args.size()) {
                    throw new IllegalArgumentException(s + " requires a value");
                }
                precision = precision(args.get(++i));
            }
            case String s when s.startsWith("--precision=") -> precision = precision(s.substring("--precision=".length()));
            case String s when s.startsWith("-p=") -> precision = precision(s.substring(3));
            // -p2 is a precision, but -pi is an expression (minus π).
            case String s when s.matches("-p(\\d+|max)") -> precision = precision(s.substring(2));
            case String s when s.startsWith("--") -> throw new IllegalArgumentException("unknown option '" + s + "'");
            default -> {
                return new Command.Run(precision, args.subList(i, args.size()));
            }
        }
        i++;
    }
    return new Command.Run(precision, List.of());
}

Output.Precision precision(String value) {
    if (value.equals("max")) {
        return new Output.Precision.Max();
    }
    try {
        int n = Integer.parseInt(value);
        if (n >= 0 && n <= 1000) {
            return new Output.Precision.Places(n);
        }
    } catch (NumberFormatException _) {
        // fall through to the error
    }
    throw new IllegalArgumentException("invalid precision '" + value + "' (expected 0 to 1000, or 'max')");
}

/// Spots `avg{1,2,3}` after the shell's brace expansion has turned it into
/// `avg1 avg2 avg3`, which would otherwise give a quietly wrong answer.
String braceExpansion(List<String> args) {
    if (args.size() < 2) {
        return null;
    }
    var prefixes = args.stream().map(this::functionPrefix).distinct().toList();
    if (prefixes.size() != 1 || prefixes.getFirst() == null) {
        return null;
    }
    var name = prefixes.getFirst();
    return "'%s' looks like the shell expanded %s{…,…}: write %s 1,2,3, %s{1 2 3} or quote it"
            .formatted(String.join(" ", args), name, name, name);
}

/// The function name that starts `arg` when a digit follows it, as in `avg1`.
String functionPrefix(String arg) {
    var lower = arg.toLowerCase(Locale.ROOT);
    return Func.NAMES.keySet().stream()
            .filter(name -> lower.startsWith(name) && lower.length() > name.length()
                    && Character.isDigit(lower.charAt(name.length())))
            .max(Comparator.comparingInt(String::length))
            .orElse(null);
}
