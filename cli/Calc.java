/// Command-line calculator: evaluates expressions, and lists of numbers from stdin.
///
///     java Calc.java 2x{3+4}                 run from source (no build step)
///     seq 10 | java Calc.java sum            stdin as a list
///     ./build.sh && ./jcalc 2x{3+4}          precompiled, with an AOT cache (~20 ms)
///
/// This compact source file (JDK 25: no class declaration, java.base imported automatically)
/// handles options, stdin and output. The other .java files in this directory hold the
/// calculator itself; the launcher compiles them on demand (multi-file source programs):
///
///     Lexer        text → tokens; decides which spaces separate list elements
///     Parser       tokens → a syntax tree of Nodes, with stdin placed where it's needed
///     Node         the sealed syntax tree, evaluated with a pattern-matching switch
///     Value        exact BigInteger fractions, falling back to double once irrational
///     Func         sum, avg, mode, stdev, round, ln, sin, factorial, ...
///     Output       result formatting rules
///     NumberStyle  decimal mark and digit grouping, for --pretty
///     Native       isatty and locale queries, via the Foreign Function & Memory API
///
/// Exit codes: 0 success, 1 a bad expression or input, 2 a bad command line.

static final String VERSION = "0.3.0";

static final String USAGE = """
        calc - evaluate arithmetic expressions on the command line
               operate on (lists of) numbers from stdin

        Usage:
          calc [OPTIONS] EXPRESSION...
          ... | calc [OPTIONS] [PARTIAL EXPRESSION...]

        Operators:  +  -  x * × · •  ÷ /  % mod  ^  ² ³  √  ∑  ∏  !
        Brackets:   {} or () or []
        Symbols:    π (pi)  @ (stdin)  ° º (degrees to radians)
        Lists:      1 2 3  or  1,2,3
        Ranges:     1..10 or 1…10
        Functions:  sqrt √  sum ∑  product prod ∏  factorial fact !  ln  log  exp
                    avg mean average x̄  median  mode  min  max  stdev  abs  round
                    floor  ceil  sin  cos  tan  asin  acos  atan  sinh  cosh  tanh

        Arithmetic applies element by element: {1 2 3}x2 is 2 4 6. Lists print one
        number per line. Adjacent terms multiply: 2π, 3√2, 2{1+1}. Group digits with _
        (1_000). An unmatched closing bracket encapsulates prior numbers: 3+4+5}/2 → 6.

        Stdin can be complete expressions or lists of values. Lines of stdin are merged
        to form one list. Partial expressions are applied to stdin. Partials that start
        with an operator continue it and arguments that end with an operator or function
        take it as their last operand. @ explicitly references stdin: 'calc max@-min@' .

        Options:
          -h, --help                     Show this help
          -p, --precision=N|max          Decimal places for non-terminating results
                                         (default 5; max = full double precision)
          --pretty[=always|auto|SAMPLE]  Read and write numbers in the local style,
                                         including digit grouping. always is the default,
                                         auto only applies to terminal output, SAMPLE
                                         explicitly sets the style, e.g. 1.234,5
          -r, --rows                     Preserve rows: each line of stdin is treated as
                                         its own list (not merged) and produces the same
                                         number of output rows.
          -V, --version                  Show version""";

// ---------------------------------------------------------------- options

enum When {
    ALWAYS, AUTO, NEVER;

    static Optional<When> parse(String value) {
        return switch (value) {
            case "always" -> Optional.of(ALWAYS);
            case "auto" -> Optional.of(AUTO);
            case "never" -> Optional.of(NEVER);
            case null, default -> Optional.empty();
        };
    }
}

/// `--pretty` as given on the command line. Unlike `CALC_PRETTY`, it also changes how
/// input is read. `style` comes from `--pretty=SAMPLE`, and is null otherwise.
record Pretty(When when, NumberStyle style) { }

record Options(Output.Precision precision, Pretty pretty, boolean rows) { }

sealed interface Command {
    record Help() implements Command { }
    record Version() implements Command { }
    record Run(Options options, List<String> expression) implements Command { }
}

/// Splits leading options from the expression. Options end at the first argument that
/// isn't one, or at `--`, so `calc -2` and `calc -pi` are expressions.
Command parseOptions(List<String> args) {
    Output.Precision precision = new Output.Precision.Default();
    Pretty pretty = null;
    boolean rows = false;
    int i = 0;
    while (i < args.size()) {
        var arg = args.get(i);
        switch (arg) {
            case "--" -> {
                return new Command.Run(new Options(precision, pretty, rows), args.subList(i + 1, args.size()));
            }
            case "-h", "--help" -> {
                return new Command.Help();
            }
            case "-V", "--version" -> {
                return new Command.Version();
            }
            case "-r", "--rows" -> rows = true;
            case "--pretty" -> pretty = new Pretty(When.ALWAYS, null);
            case String s when s.startsWith("--pretty=") -> {
                var value = s.substring("--pretty=".length());
                pretty = When.parse(value).map(when -> new Pretty(when, null))
                        .orElseGet(() -> new Pretty(When.ALWAYS, NumberStyle.fromExample(value)));
            }
            case "-p", "--precision" -> {
                if (i + 1 >= args.size()) {
                    throw new CalcError.Usage(arg + " requires a value");
                }
                precision = precision(args.get(++i));
            }
            case String s when s.startsWith("--precision=") -> precision = precision(s.substring("--precision=".length()));
            case String s when s.startsWith("--precision") -> throw new CalcError.Usage("unknown option '" + s + "'");
            case String s when s.startsWith("-p=") -> precision = precision(s.substring(3));
            // -p2 is a precision, but -pi is an expression (minus π).
            case String s when s.matches("-p(\\d+|max)") -> precision = precision(s.substring(2));
            case String s when s.startsWith("--") -> throw new CalcError.Usage("unknown option '" + s + "'");
            default -> {
                return new Command.Run(new Options(precision, pretty, rows), args.subList(i, args.size()));
            }
        }
        i++;
    }
    return new Command.Run(new Options(precision, pretty, rows), List.of());
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
    throw new CalcError.Usage("invalid precision '" + value + "' (expected an integer or 'max')");
}

/// Spots `avg{1,2,3}` after the shell's brace expansion has turned it into
/// `avg1 avg2 avg3`, which would otherwise give a quietly wrong answer.
void checkBraceExpansion(List<String> args) {
    if (args.size() < 2) {
        return;
    }
    var prefixes = args.stream().map(this::functionPrefix).distinct().toList();
    if (prefixes.size() == 1 && prefixes.getFirst() != null) {
        var name = prefixes.getFirst();
        throw new CalcError.Usage("'%s' looks like the shell expanded %s{…,…}: write %s 1,2,3, %s{1 2 3} or quote it"
                .formatted(String.join(" ", args), name, name, name));
    }
}

/// The function name that starts `arg` when a digit follows it, as in `avg1`.
String functionPrefix(String arg) {
    var lower = arg.toLowerCase(Locale.ROOT);
    return Func.prefixOf(lower)
            .map(Map.Entry::getKey)
            .filter(name -> lower.length() > name.length() && Character.isDigit(lower.charAt(name.length())))
            .orElse(null);
}

// ---------------------------------------------------------------- output

/// Buffered UTF-8 output; flushed explicitly, so a large result is written in big batches
/// but a live feed (`tail -f | calc x2`) still sees each line at once.
final PrintStream out = new PrintStream(new BufferedOutputStream(new FileOutputStream(FileDescriptor.out), 1 << 16),
        false, StandardCharsets.UTF_8);
final PrintStream err = new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8);

/// Formats results: plain by default, or in a local style when pretty.
record Printer(Output.Precision precision, NumberStyle pretty) {
    String format(Value value) {
        var decimal = Output.format(value, precision);
        return pretty == null ? decimal.toString() : pretty.apply(decimal);
    }

    void writeLines(PrintStream out, List<Value> values) {
        for (var v : values) {
            out.println(format(v));
        }
    }

    void writeRow(PrintStream out, List<Value> values) {
        out.println(String.join(" ", values.stream().map(this::format).toList()));
    }
}

int fail(CalcError error, String source) {
    out.flush(); // keep stderr in order with stdout
    err.println("calc: " + error.inSource(source));
    return error.exitCode();
}

// ---------------------------------------------------------------- terminals

/// Whether fd 0 (stdin) or 1 (stdout) is a terminal: exact via isatty when native access
/// is enabled. Otherwise the JDK can only say whether stdin *and* stdout both are
/// (Console.isTerminal), which is right for the common cases. Looked up only when needed.
boolean isTerminal(int fd) {
    return Native.isTerminal(fd).orElseGet(() -> System.console() != null && System.console().isTerminal());
}

// ---------------------------------------------------------------- main

void main(String[] args) {
    int status;
    try {
        status = run(List.of(args));
    } catch (CalcError e) {
        status = fail(e, "");
    }
    out.flush();
    // A reader that went away (calc ... | head) isn't an error: there's nothing left to do.
    System.exit(out.checkError() && status == 0 ? 0 : status);
}

int run(List<String> args) {
    var command = parseOptions(args);
    return switch (command) {
        case Command.Help _ -> {
            out.println(USAGE);
            yield 0;
        }
        case Command.Version _ -> {
            out.println("calc (Java) " + VERSION);
            yield 0;
        }
        case Command.Run(var options, var expression) -> calculate(options, expression);
    };
}

int calculate(Options options, List<String> rest) {
    if (rest.stream().anyMatch(a -> a.indexOf('�') >= 0)) {
        throw new CalcError.Usage("the arguments contain characters that couldn't be decoded. Use a UTF-8 locale, "
                + "or the ASCII spellings: sqrt, pi, x, ^2");
    }
    checkBraceExpansion(rest);

    // CALC_PRETTY only changes output, so it never changes what a script's input means.
    var pretty = options.pretty();
    var when = pretty != null ? pretty.when() : When.parse(System.getenv("CALC_PRETTY")).orElse(When.NEVER);
    boolean writePretty = switch (when) {
        case ALWAYS -> true;
        case AUTO -> isTerminal(1);
        case NEVER -> false;
    };
    boolean readPretty = pretty != null && pretty.when() != When.NEVER;
    Supplier<NumberStyle> style = () -> pretty != null && pretty.style() != null ? pretty.style() : NumberStyle.detected();
    var syntax = new Lexer.Syntax(readPretty ? style.get() : null);
    var printer = new Printer(options.precision(), writePretty ? style.get() : null);

    if (rest.isEmpty() && isTerminal(0)) {
        err.println(USAGE);
        return 2;
    }

    // Arguments are joined with spaces, which separate list elements: `calc 1 2 3`.
    var source = String.join(" ", rest);
    Parser.Expression expression;
    try {
        expression = Parser.Expression.parse(source, syntax);
    } catch (CalcError e) {
        return fail(e, source);
    }
    // Only read stdin when the arguments say so or are missing an operand, so `calc 1+2` in
    // a `while read` loop doesn't swallow the loop's input.
    boolean incomplete = expression.isContinuation() || expression.awaitsOperand();
    boolean readsStdin = expression.isEmpty() || expression.readsStdin() || (incomplete && !isTerminal(0));
    if (readsStdin && isTerminal(0)) {
        throw new CalcError.Usage("'@' reads from stdin, but nothing is piped in");
    }
    // A lone negative number continues stdin (`calc 2x5 | calc -1` is 9), but if stdin turns
    // out to be empty it is just the number, so `calc $x` still works under cron.
    Parser.Program ifEmpty = null;
    if (readsStdin && expression.isLoneNegative()) {
        ifEmpty = Parser.Expression.parse(source, syntax).compile();
    }
    if (readsStdin) {
        expression.feedStdin();
    }
    // Syntax errors show up here, before any stdin is read.
    Parser.Program program;
    try {
        program = expression.compile();
    } catch (CalcError e) {
        return fail(e, source);
    }
    if (readsStdin) {
        return runStdin(program, ifEmpty, syntax, printer, options.rows());
    }
    try {
        printer.writeLines(out, program.evaluate(null));
        return 0;
    } catch (CalcError e) {
        return fail(e, source);
    }
}

// ---------------------------------------------------------------- stdin

/// A non-blank line of stdin and its line number.
record Line(int number, String text) {
    List<Value> values(Lexer.Syntax syntax) {
        if (text.indexOf('�') >= 0) {
            throw new CalcError.Input("input is not valid UTF-8 (check your locale)");
        }
        return Parser.evaluate(text, syntax);
    }
}

/// Evaluates the program with stdin. By default all of stdin is one list, but when the
/// program only works element by element each line is evaluated as it arrives, which gives
/// the same output while streaming. `--rows` evaluates every line on its own. `ifEmpty` is
/// evaluated instead when stdin has no lines at all.
int runStdin(Parser.Program program, Parser.Program ifEmpty, Lexer.Syntax syntax, Printer printer, boolean rows) {
    // Stdin is read as UTF-8 whatever the locale; a malformed byte becomes U+FFFD.
    var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8), 1 << 16);
    boolean failed = false;

    if (rows || program.streams()) {
        boolean empty = true;
        for (var line : (Iterable<Line>) lines(reader)::iterator) {
            empty = false;
            try {
                List<Value> values;
                try {
                    values = line.values(syntax);
                } catch (CalcError e) {
                    failed = report(line.number(), e, line.text());
                    continue;
                }
                var result = program.evaluate(values);
                if (rows) {
                    printer.writeRow(out, result);
                } else {
                    printer.writeLines(out, result);
                }
            } catch (CalcError e) {
                failed = report(line.number(), e, program.source());
            } finally {
                // Flush before waiting on more input, so a live feed (`tail -f`) sees each
                // result at once while a large file is still written in big batches.
                if (!ready(reader)) {
                    out.flush();
                    if (out.checkError()) {
                        return 0; // the reader went away
                    }
                }
            }
        }
        if (empty && ifEmpty != null) {
            try {
                printer.writeLines(out, ifEmpty.evaluate(null));
            } catch (CalcError e) {
                failed = report(0, e, ifEmpty.source());
            }
        }
        return failed ? 1 : 0;
    }

    // The whole list is needed (sum, median, ...): read everything, and give no result if any
    // line is bad, since a total that silently skips a line is worse than none.
    var values = new ArrayList<Value>();
    for (var line : (Iterable<Line>) lines(reader)::iterator) {
        try {
            values.addAll(line.values(syntax));
        } catch (CalcError e) {
            failed = report(line.number(), e, line.text());
        }
    }
    if (!failed) {
        try {
            printer.writeLines(out, program.evaluate(values));
        } catch (CalcError e) {
            failed = report(0, e, program.source());
        }
    }
    return failed ? 1 : 0;
}

/// The non-blank lines of `reader`, numbered from 1 (blank lines still count).
Stream<Line> lines(BufferedReader reader) {
    var number = new int[1];
    return reader.lines()
            .map(text -> new Line(++number[0], text))
            .filter(line -> !line.text().isBlank());
}

/// True when more input can be read without waiting.
boolean ready(BufferedReader reader) {
    try {
        return reader.ready();
    } catch (IOException e) {
        return false;
    }
}

/// Reports an error on stderr, in order with stdout, prefixed with its line of input (when
/// it has one). Returns true, for `failed = report(...)`.
boolean report(int line, CalcError error, String source) {
    out.flush();
    err.println("calc: " + (line > 0 ? "line " + line + ": " : "") + error.inSource(source));
    return true;
}
