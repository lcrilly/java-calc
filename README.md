# java-calc

**Modern Java, one use case, several form factors.**

This repo is a hands-on showcase of what Java looks like today: Java 21–25 language features,
current tooling, and the Azul Zulu JDK. The vehicle is a calculator, because a calculator is
stateless, needs no storage, and everyone knows what a correct answer looks like. That keeps the
code about Java rather than about the domain.

Each form factor is a **separate, self-contained implementation** with no shared code. That's
deliberate. Each one uses the idioms that suit its shape and can be read on its own, and side by
side they show how differently the same problem can be solved with the same language.

| Form factor | Directory | What it is | Java on show |
|-------------|-----------|------------|--------------|
| **Web app** | [`web/`](web/README.md) | Inspired by **[lcrilly/unit-calculator](https://github.com/lcrilly/unit-calculator)** - a desktop calculator in the browser, backed by a JSON REST API, shipped as a container. | Spring Boot 4, sealed types and record patterns in a service layer, `jdeps` + `jlink` custom runtime |
| **Command line** | [`cli/`](cli/README.md) | Inspired by **[lcrilly/calc-cli](https://github.com/lcrilly/calc-cli)** - an expression calculator for the shell, with lists, ranges, functions and stdin pipelines. | Compact source files, multi-file source launch (no build step), module imports, a sealed expression tree, the Foreign Function & Memory API, an AOT cache for fast startup |

Both use exact arithmetic, so `0.1 + 0.2` is exactly `0.3`.

## Getting started

You need **JDK 25**. Everything here is developed and tested on
[Azul Zulu](https://www.azul.com/downloads/?package=jdk) 25:

```shell
brew install --cask zulu@25            # macOS (Homebrew)
sdk install java 25.0.x-zulu           # or SDKMAN: `sdk list java | grep zulu` for the exact version
java -version                          # expect "Zulu25..." and "LTS"
```

Then pick a form factor:

```shell
cd cli && java Calc.java "2x{3+4}"      # 14, no build needed
cd cli && ./build.sh && ./jcalc 2x{3+4} # 14, about 25× faster once built
cd web && ./mvnw spring-boot:run        # http://localhost:9000 (Maven is fetched by the wrapper)
```

The web app can also run in Docker with no local JDK at all. See [`web/README.md`](web/README.md).

## Modern Java feature index

Where to look for each feature:

| Feature | JDK | Where |
|---------|-----|-------|
| Records | 16 | everywhere: `web` request, response and outcome types; `cli` tokens and expression nodes |
| Sealed interfaces and classes | 17 | `web` `Operation`, `Outcome`; `cli` `Value`, `Expr`, `Tok`, `CalcError` |
| Pattern matching for `switch` | 21 | `web` `CalculatorController`, `Calculator`; `cli` `Parser`, `Value`, `Output` |
| Record patterns (deconstruction) | 21 | `web` `case Binary(var _, var fold)`; `cli` `case Binary(var op, var left, var right)` |
| Sequenced collections (`getFirst`, `addFirst`) | 21 | `web` `Calculator`; `cli` `Lexer`, `Parser`, `Func` |
| Unnamed variables and patterns (`_`) | 22 | `web` `Calculator`; `cli` `Lexer` (`case Tok.Num _, Tok.Pi _ ->`) |
| Multi-file source programs | 22 | `cli`: `java Calc.java` compiles the other files on demand |
| Foreign Function & Memory API | 22 | `cli/Native.java`: `isatty`, `localeconv` and macOS CoreFoundation, without JNI |
| Markdown documentation comments (`///`) | 23 | `cli` |
| Compact source files and instance `main` | 25 | `cli/Calc.java` |
| Module import declarations (`import module java.base;`) | 25 | `cli` |
| Text blocks | 15 | `cli` usage text |
| `BigDecimal` / `BigInteger` exact arithmetic | — | `web` 34-digit decimals; `cli` exact fractions |
| `jdeps` + `jlink` custom runtime | 9+ | `web/Dockerfile`: a 67 MB runtime of only the modules the app needs |
| AOT cache (ahead-of-time class loading, linking and method profiles) | 24–25 | `cli/build.sh` and `cli/jcalc`: startup from ~640 ms to ~25 ms |

## Future development

Ideas for further form factors and experiments, each showing something new:

- **Desktop app** with JavaFX (Azul publishes Zulu builds that include JavaFX)
- **Native executable** with GraalVM Native Image, to compare startup with the JIT
- **CRaC** (Coordinated Restore at Checkpoint) on Zulu, for near-instant JVM startup
- **OpenTelemetry tracing** of the web app, with the Java agent or Spring Boot's own support
