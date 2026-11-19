package dev.javacalc.calc;

import java.math.BigDecimal;

/** The result of asking the calculator to do something: exactly one of these four cases. */
public sealed interface Outcome {
    record Ok(BigDecimal value) implements Outcome { }
    record UnknownOperation(String name) implements Outcome { }
    record BadOperands(String reason) implements Outcome { }
    record Undefined(String reason) implements Outcome { }
}
