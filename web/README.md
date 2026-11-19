# java-calc: web app

A classic desktop calculator (the Tally DS-812) in the browser, backed by a JSON REST API written
in modern Java. One Spring Boot process serves both the page and the API, and the whole thing ships
as a small container.

Part of the [java-calc](../README.md) showcase.

## Run

From this directory:

```sh
./mvnw spring-boot:run                 # http://localhost:9000 (set PORT to change it)
./mvnw test                            # 33 tests: maths and the HTTP contract
```

Or with Docker, which needs no local JDK. The build runs the tests, so a failing test fails the build:

```sh
docker build -t java-calc .
docker run -p 9000:9000 java-calc
```

The browser does no arithmetic. Every key press that needs a result calls the API.

## API

`POST /api/{operation}` with `{"operands": [...]}` returns `{"result": "<decimal string>"}`.

| Operation  | Operands  | Meaning                  |
|------------|-----------|--------------------------|
| `add`      | 2 or more | sum                      |
| `subtract` | 2 or more | left-to-right difference |
| `multiply` | 2 or more | product                  |
| `divide`   | 2 or more | left-to-right quotient   |
| `sqroot`   | 1         | square root              |
| `square`   | 1         | x²                       |
| `inverse`  | 1         | 1/x                      |
| `power`    | 2         | x^y                      |
| `pi`       | 0 (`[]`)  | π to 34 digits           |

```sh
curl -d '{"operands": [0.1, 0.2]}' -H 'Content-Type: application/json' localhost:9000/api/add
# {"result":"0.3"}
```

- `GET /api/operations` lists the operations.
- A bare number is accepted for a single operand: `{"operands": 81}`.
- Errors are [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) problem details: `400` bad
  operands, `404` unknown operation, `422` undefined maths (division by zero, √ of a negative).
- Arithmetic uses `BigDecimal` with 34 significant digits. Results are **strings**, so no client
  loses precision by parsing them as doubles.

## Architecture

```
Browser ─ index.html ──────────────────────────────────────────────────────────
  key presses → calculator state machine → POST /api/{op} {"operands":[…]} → LCD
                                              │
Container (debian:trixie-slim + jlinked Zulu 25 runtime) ─────────────────────
  Spring Boot 4.1 (embedded Tomcat, :9000)    ▼
    static/index.html  ← GET /         CalculatorController  ← /api/**
                                              │ evaluate(name, operands)
                                       Calculator ── looks up ──▶ Operations
                                              │                  (sealed Operation records)
                                       Outcome (sealed) → 200 result | 400 / 404 / 422 problem
```

| Component | File | Role |
|-----------|------|------|
| Front end | `src/main/resources/static/index.html` | One self-contained page with no framework: an SVG seven-segment LCD, the keypad, and a state machine for chaining, repeated `=` and memory. Key presses are queued, so requests never overtake each other. Operands are sent as exact decimal strings. |
| Controller | `CalculatorController.java` | Binds the JSON body, calls the service, and maps each `Outcome` to an HTTP response with an exhaustive `switch`. |
| Service | `Calculator.java` | Looks up the operation, checks how many operands it takes, and computes with record patterns: `case Binary(var _, var fold) ->`. |
| Operations | `Operation.java`, `Operations.java` | A sealed interface with four shapes (`Constant`, `Unary`, `Binary`, `Variadic`), each a record holding a lambda. `Operations` lists them all by URL path. |
| Results | `Outcome.java` | A sealed result type (`Ok`, `UnknownOperation`, `BadOperands`, `Undefined`), so errors are values rather than exceptions leaking out of the service. |

Adding an operation of an existing shape is one line in `Operations`. Adding a new *shape* won't
compile until every `switch` handles it. The sealed hierarchy enforces that, and it's how `pi`
(the `Constant` shape) was added.

The server is stateless: memory and the pending operator live in the browser. You can run as many
instances behind a load balancer as you like, with no session affinity.

## Container

`Dockerfile` is a two-stage build:

1. **Build** on `azul/zulu-openjdk:25`: run `mvnw verify` (tests included), extract the Spring Boot
   jar, use `jdeps` to find the JDK modules the app uses, then `jlink` a custom runtime of only
   those (67 MB).
2. **Run** on `debian:trixie-slim` with that runtime, as a non-root user.

The result is about 294 MB, against about 490 MB for an image based on the stock Zulu JRE, and
it starts in about half a second.

## Keyboard

`0–9` `.` `+ − × ÷` `^` (power) `R` (√) `P` (π) `Enter` (=) `Esc` (AC) `Delete` (C) `⌫`
