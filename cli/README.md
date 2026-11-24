# java-calc: command line

An expression calculator for the shell, with lists, functions and stdin pipelines. Run it
straight from source with no build step, or build it in about a second for a much faster
command. There are no dependencies either way. It is a Java port of
[calc-cli](https://github.com/lcrilly/calc-cli), a Rust tool for the same job, and keeps up
with its features.

Part of the [java-calc](../README.md) showcase. Requires JDK 25.

## Usage

Two ways to run it:

```sh
# 1. From source: no build step. The JDK compiles all the files in memory on every run.
java --enable-native-access=ALL-UNNAMED Calc.java 2x{3+4}

# 2. Built: a precompiled jar with an AOT cache (~0.02 s). Rebuild after editing the sources
#    or upgrading the JDK; ./jcalc warns if the sources are newer than the build.
./build.sh
./jcalc 2x{3+4}
```

To make it a command, put this directory on your `PATH`, or:

```sh
alias jcalc=/path/to/java-calc/cli/jcalc                                         # built (fast)
alias jcalc='java --enable-native-access=ALL-UNNAMED /path/to/java-calc/cli/Calc.java'  # from source
```

`--enable-native-access` lets calc ask the C library whether stdin is a terminal and how the
locale writes numbers (see [Native access](#native-access)). The built jar enables it in its
manifest. Without it calc still works, using slightly less precise pure-Java fallbacks.

Examples:

```sh
jcalc 0.1+0.2                 # 0.3      exact decimal arithmetic
jcalc {3x4}^2                 # 144      shell-safe brackets and ×
jcalc 15+4+3}/2               # 11       an unmatched } wraps everything before it
jcalc 2π12                    # 75.39822 adjacent terms multiply
jcalc avg 3,4,8               # 5        functions take the rest of the list
jcalc sum 1..100              # 5050     ranges
jcalc {1 2 3}x2               # 2 4 6    arithmetic applies to each element (one per line)
jcalc 'stdev 2 4 4 4 5 5 7 9' # 2.13809
jcalc 3^100                   # all 48 digits, exactly
jcalc 'sin 30°'               # 0.5      degrees to radians
jcalc 5! x2                   # 240      factorial
seq 1 10 | jcalc sum          # 55       a list from stdin
date +%s | jcalc +60x60x24    #          apply a partial expression to stdin
```

## Expressions

| Operators            | Meaning                                    |
|----------------------|--------------------------------------------|
| `+` `-`              | add, subtract                              |
| `*` `x` `×` `·` `•`  | multiply; adjacent terms also multiply: `2π`, `3√2`, `2{1+1}` |
| `/` `÷`              | divide                                     |
| `%` `mod`            | modulo, taking the sign of the divisor: `-7 mod 3` is 2 |
| `^` `²` `³`          | power (`^` is right-associative: `2^3^2` is 512) |
| `!`                  | factorial, binding tightest of all: `-3!` is -6, `2^3!` is 64, `√4!` is √24 |

| Symbols              | Meaning                                    |
|----------------------|--------------------------------------------|
| `{}` `()` `[]`       | brackets, nestable; an unmatched closer wraps everything before it |
| `1 2 3` or `1,2,3`   | a list (`1 -2` is a list; `1 - 2` is -1)   |
| `1..10` `1…10`       | a range, stepping by +1 or -1              |
| `√` `sqrt`           | square root of the term after it: `√4²` is `(√4)²` |
| `π` `pi`             | π                                          |
| `°` `º`              | degrees to radians, binding like `!`: `sin 30°` is 0.5; `asin{0.5}/1°` is 30 |
| `@`                  | stdin, as a list (see [Stdin](#stdin))     |
| `1_000` `1'000`      | digit grouping                             |

| Functions                      | Result                                  |
|--------------------------------|-----------------------------------------|
| `sum` `∑` `Σ`                  | total (0 for an empty list)             |
| `product` `prod` `∏` `Π`       | product                                 |
| `avg` `average` `mean` `x̄`     | arithmetic mean                         |
| `median` `min` `max`           |                                         |
| `mode`                         | most frequent (returns all ties)        |
| `stdev`                        | sample standard deviation (n − 1)       |
| `abs` `floor` `ceil` `round`   | applied to each element                 |
| `factorial` `fact` `!`         | each element; exact for any size that fits in memory |
| `ln` `log` `exp`               | each element; `log` is base 10          |
| `sin` `cos` `tan`              | each element; radians                   |
| `asin` `acos` `atan`           | each element; inverse, in radians       |
| `sinh` `cosh` `tanh`           | each element; hyperbolic                |

A function name followed directly by a bracket takes that bracket (`sum{1 2}x2` is 6);
otherwise it takes the rest of the list (`sum 1 2 x2` is `sum(1, 2×2)`, 5). Names are
case-insensitive.

## Output

- Integers stay integers: `7/7` → `1`. Fractions that terminate print in full: `1/64` → `0.015625`.
- Recurring and irrational results print to 5 decimal places, or `-p N` (`--precision=N`);
  `-p max` shows everything a double holds.
- A list prints one number per line.
- Exit codes: `0` success, `1` a bad expression or input (syntax, division by zero, ...), `2` a
  bad command line (unknown option, ...). Errors point at the part of the expression that failed:
  ```
  $ jcalc 1+2/0
  calc: division by zero
    1+2/0
      ^^^
  ```

## Stdin

- With no expression arguments, each line of stdin is evaluated and printed.
- Lines of stdin are merged into one list: `seq 1 10 | jcalc sum` is 55. With `-r` (`--rows`),
  each line is its own list and gives one row of output: `printf '1 2\n3 4\n' | jcalc -r sum`.
- Arguments that start with an operator continue stdin: `seq 3 | jcalc x2`. So does a lone
  negative number: `jcalc 2x5 | jcalc -1` is 9 (with nothing on stdin it is just the number).
- Arguments that end with an operator or function take stdin as their last operand:
  `seq 3 | jcalc 2^`. An unmatched closing bracket wraps stdin too: `seq 3 | jcalc +4}/2`.
- `@` places stdin explicitly, as often as needed: `seq 1 20 | jcalc max@-min@`.
- Complete arguments never read stdin, so `jcalc 1+2` is safe inside a `while read` loop.
- When the expression works element by element, each result is printed as its line arrives,
  so `tail -f log | jcalc x2` works. A bad line is reported with its line number and the rest
  carry on (exit status 1). When the whole list is needed (`sum`, `avg`, ...), a bad line means
  no result at all, since a total that silently skipped a line would be wrong.

## Pretty numbers

`--pretty` writes numbers in the local style, with digit grouping, and reads stdin the same way:

```sh
jcalc --pretty -p1 1000π                         # 3,141.6 (en_US)   3.141,6 (de_DE)
jcalc --pretty -p1 1000π | jcalc --pretty +1     # 3,142.6: "3,141.6" is read as one number
jcalc --pretty=0,00,000.0 ∑1…1234}/2             # 3,80,997.5: give an example of the style
```

- `--pretty=auto` applies only when stdout is a terminal; `--pretty=never` turns it off.
- `CALC_PRETTY=auto` (or `always`/`never`) sets the default for output only, never input, so
  `CALC_PRETTY=auto` in a shell profile shows `3,141.6` in the terminal while
  `jcalc 1000π | jcalc +1` still works.
- The style comes from, in order: `--pretty=SAMPLE`, `CALC_NUMBER_FORMAT=SAMPLE`,
  `LC_ALL`/`LC_NUMERIC` if set, macOS System Settings → Language & Region (including custom
  formats), then `LANG`.
- Where the locale writes decimals with a comma, plain `3,5` is an error rather than a list:
  write `3.5`, or `3, 5` for the list.

## Shell notes

- `(`, `)` and `*` need quoting; the shell-safe spellings `{}` and `x` don't.
- Avoid commas or `..` inside `{}` unquoted: bash's brace expansion turns `avg{1,2,3}` into
  `avg1 avg2 avg3` (which calc detects and reports) and `{1..3}` into `1 2 3`.
- zsh rejects a word ending in `}` when its `{` is in another word: write `2x{3+4}` or quote it.
- Unicode symbols need a UTF-8 locale. In a `C` or `POSIX` locale (common in containers) the
  JVM can't decode them, so calc stops and suggests the ASCII spellings: `sqrt`, `pi`, `x`, `^2`.

## How it works

`Calc.java` is the entry point; the launcher compiles the other files in this directory as they are
needed ([multi-file source programs](https://openjdk.org/jeps/458), JDK 22+).

| File | Role | Java on show |
|------|------|--------------|
| `Calc.java` | options, stdin, output, exit codes | Compact source file (no class declaration, `java.base` imported automatically) |
| `Lexer.java` | text → tokens; which spaces separate list elements | Sealed interface of token records, unnamed patterns (`case Num _, Pi _ ->`) |
| `Parser.java` | tokens → syntax tree; where stdin goes; inferred brackets | Recursive descent |
| `Node.java` | the syntax tree, with input positions; evaluation; streaming analysis | A sealed tree of records, evaluated by one exhaustive `switch` with record patterns |
| `Value.java` | numbers | Exact `BigInteger` fractions (never overflow), falling back to `double` once irrational |
| `Func.java` | named functions | Enum with exhaustive `switch`es |
| `Output.java` | formatting rules | `BigDecimal` rounding of a double's exact value |
| `NumberStyle.java` | decimal mark and digit grouping | A record parsed from an example such as `1,234.5` |
| `Native.java` | terminal and locale queries | Foreign Function & Memory API: `isatty`, `localeconv`, CoreFoundation |
| `CalcError.java` | errors with positions | Sealed exception hierarchy, mapped to exit codes by pattern matching |

The other files use `import module java.base;` ([module imports](https://openjdk.org/jeps/511), JDK 25).

## Native access

Three things the JDK can't tell a Java program on its own, so `Native.java` asks the operating
system with the [Foreign Function & Memory API](https://openjdk.org/jeps/454) (JDK 22):

| Question | Native call | Pure-Java fallback |
|----------|-------------|--------------------|
| Is stdin (or stdout) a terminal? | `isatty(0)`, `isatty(1)` | `System.console().isTerminal()`, which is only true when *both* are |
| How does the locale write numbers? | `setlocale` + `localeconv`, as C programs do | the JDK's locale data, which has no Indian-style grouping (`12,34,567`) |
| What are my macOS number settings? | CoreFoundation's `CFNumberFormatter` | none (the `LANG` locale is used) |

Native calls are "restricted": the JVM prints a warning unless they are enabled with
`--enable-native-access=ALL-UNNAMED` (or `Enable-Native-Access` in a jar manifest, as
`build.sh` does). calc checks `Module.isNativeAccessEnabled()` first and uses the fallbacks
when it isn't, so there is never a warning.

## Startup time

Running from source, about 95% of each call is spent compiling. Measured on Zulu 25 for a
typical expression:

| How it runs | Per call |
|-------------|----------|
| `java Calc.java` (compiles ~2,000 lines every time) | ~640 ms |
| … plus `-XX:TieredStopAtLevel=1` | ~535 ms |
| `./jcalc`: precompiled jar + AOT cache + `-XX:TieredStopAtLevel=1` | **~25 ms** |
| `./jcalc --pretty` (also asks the C library for the locale's number format) | ~30 ms |

What `./build.sh` and `./jcalc` do:

- **Compile once:** plain `javac` and `jar` (no Maven) produce `build/calc.jar`.
- **AOT cache** ([JEP 483](https://openjdk.org/jeps/483), [514](https://openjdk.org/jeps/514),
  [515](https://openjdk.org/jeps/515), JDK 24–25): one training run with
  `-XX:AOTCacheOutput` records the classes the CLI loads, already parsed and linked, plus
  method profiles, in `build/calc.aot`. Later runs map that file instead of redoing the work.
- **C1 only** (`-XX:TieredStopAtLevel=1`): a short-lived process never benefits from the
  optimising C2 compiler, so don't start it.
- **Safe fallback:** the cache is tied to the exact jar and JDK build. `./jcalc` only uses it
  when it is newer than the jar, and passes `-Xlog:aot*=off`, because a stale cache otherwise
  makes the JVM print warnings on *stdout*, mixed in with the results.

The two modes behave identically; the test suite runs against both.

**Differences from calc-cli.** Exact results use `BigInteger`, so `3^100`, `product 1..30` and
`34!` stay exact where calc-cli's 128-bit integers switch to floating point. Ranges are capped at
1,000,000 numbers (calc-cli: 10,000,000), since a million `BigInteger` fractions already use a
lot more memory than Rust's fixed-size values.

## Tests

```sh
./test.sh            # from source: about 1.5 minutes
./test.sh --built    # builds, then tests ./jcalc: about 5 seconds
```

About 270 end-to-end cases, most ported from calc-cli's own tests. Each runs the calculator exactly
as a user would and checks stdout, stderr and the exit code, including live streaming, `| head`,
and locale detection (when the de_DE and en_IN locales are installed).
