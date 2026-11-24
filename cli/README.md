# java-calc: command line

An expression calculator for the shell, with lists and functions. Run it straight from source
with no build step, or build it in under a second for a 25× faster command. There are no
dependencies either way.
It mirrors the expression language of [calc-cli](https://github.com/lcrilly/calc-cli), a
Rust tool for the same job.

Part of the [java-calc](../README.md) showcase. Requires JDK 25.

## Usage

Two ways to run it:

```sh
# 1. From source: no build step. The JDK compiles all the files in memory on every run (~0.55 s).
java Calc.java 2x{3+4}

# 2. Built: a precompiled jar with an AOT cache (~0.02 s). Rebuild after editing the sources
#    or upgrading the JDK; ./jcalc warns if the sources are newer than the build.
./build.sh
./jcalc 2x{3+4}
```

To make it a command, put this directory on your `PATH`, or:

```sh
alias jcalc=/path/to/java-calc/cli/jcalc               # built (fast)
alias jcalc='java /path/to/java-calc/cli/Calc.java'    # from source
```

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
```

## Expressions

| Operators            | Meaning                                    |
|----------------------|--------------------------------------------|
| `+` `-`              | add, subtract                              |
| `*` `x` `×` `·` `•`  | multiply; adjacent terms also multiply: `2π`, `3√2`, `2{1+1}` |
| `/` `÷`              | divide                                     |
| `%` `mod`            | modulo, taking the sign of the divisor: `-7 mod 3` is 2 |
| `^` `²` `³`          | power (`^` is right-associative: `2^3^2` is 512) |

| Symbols              | Meaning                                    |
|----------------------|--------------------------------------------|
| `{}` `()` `[]`       | brackets, nestable; an unmatched closer wraps everything before it |
| `1 2 3` or `1,2,3`   | a list (`1 -2` is a list; `1 - 2` is -1)   |
| `1..10` `1…10`       | a range, stepping by +1 or -1              |
| `√` `sqrt`           | square root of the term after it: `√4²` is `(√4)²` |
| `π` `pi`             | π                                          |
| `1_000` `1'000`      | digit grouping                             |

| Functions                      | Result                                  |
|--------------------------------|-----------------------------------------|
| `sum` `∑` `Σ`                  | total (0 for an empty list)             |
| `product` `prod` `∏` `Π`       | product                                 |
| `avg` `average` `mean` `x̄`     | arithmetic mean                         |
| `median` `min` `max`           |                                         |
| `stdev`                        | sample standard deviation (n − 1)       |
| `abs` `floor` `ceil` `round`   | applied to each element                 |
| `ln` `log` `exp`               | each element; `log` is base 10          |
| `sin` `cos` `tan`              | each element; radians                   |

A function name followed directly by a bracket takes that bracket (`sum{1 2}x2` is 6);
otherwise it takes the rest of the list (`sum 1 2 x2` is `sum(1, 2×2)`, 5). Names are
case-insensitive.

## Output

- Integers stay integers: `7/7` → `1`. Fractions that terminate print in full: `1/64` → `0.015625`.
- Recurring and irrational results print to 5 decimal places, or `-p N` (`--precision=N`);
  `-p max` shows everything a double holds.
- A decimal point in the input keeps one in the output: `2.5x2` → `5.0`.
- A list prints one number per line.
- Exit codes: `0` success, `1` maths error (division by zero, `√-1`, ...), `2` usage or syntax
  error. Syntax errors point at the problem:
  ```
  $ jcalc "12+*3"
  calc: unexpected '*'
    12+*3
       ^
  ```

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
| `Calc.java` | options, output, exit codes | Compact source file (no class declaration, `java.base` imported automatically) |
| `Lexer.java` | text → tokens; which spaces separate list elements; inferred brackets | Sealed interface of token records, unnamed patterns (`case Tok.Num _, Tok.Pi _ ->`) |
| `Parser.java` | tokens → expression tree → values | Recursive descent; a sealed tree of records evaluated by one exhaustive `switch` with record patterns |
| `Value.java` | numbers | Exact `BigInteger` fractions (never overflow), falling back to `double` once irrational |
| `Func.java` | named functions | Enum with an exhaustive `switch` |
| `Output.java` | formatting rules | `BigDecimal` rounding of a double's exact value |
| `CalcError.java` | syntax vs maths errors | Sealed exception hierarchy, mapped to exit codes by pattern matching |

The other files use `import module java.base;` ([module imports](https://openjdk.org/jeps/511), JDK 25).

## Startup time

Running from source, about 95% of each call is spent compiling. Measured on Zulu 25 for a
typical expression:

| How it runs | Per call |
|-------------|----------|
| `java Calc.java` (compiles ~1,160 lines every time) | ~550 ms |
| … plus `-XX:TieredStopAtLevel=1` | ~450 ms |
| … plus an AOT cache of javac's own classes | ~315 ms |
| `./jcalc`: precompiled jar + AOT cache + `-XX:TieredStopAtLevel=1` | **~20 ms** |

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

**Differences from calc-cli.** No stdin input (`@`, partial expressions, `--rows`) and no
`--pretty` locale formatting yet. Exact results use `BigInteger`, so `3^100` and `product 1..30` stay
exact where calc-cli switches to floating point. Ranges are capped at 1,000,000 numbers. Syntax
errors exit with `2` rather than `1`.

## Tests

```sh
./test.sh            # from source: about 1.5 minutes
./test.sh --built    # builds, then tests ./jcalc: about 5 seconds
```

176 end-to-end cases, most ported from calc-cli's own tests. Each runs the calculator exactly as a
user would and checks stdout, stderr and the exit code.
