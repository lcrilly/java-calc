package dev.javacalc.calc;

import java.math.BigDecimal;
import java.math.MathContext;

/**
 * A mathematical operation, modelled by how many operands it takes.
 * <p>
 * The hierarchy is sealed, so the {@code switch} in {@link Calculator} is checked exhaustively by the compiler:
 * adding a new shape of operation without handling it is a compile error.
 */
public sealed interface Operation {

    /** URL path segment, e.g. {@code add} for {@code POST /api/add}. */
    String path();

    /** No operands: a mathematical constant such as pi. */
    record Constant(String path, Value value) implements Operation { }

    /** Two or more operands, folded left to right: {@code ((a op b) op c) ...}. */
    record Variadic(String path, Fold fold) implements Operation { }

    /** Exactly one operand. */
    record Unary(String path, Function function) implements Operation { }

    /** Exactly two operands, where order matters and folding makes no sense (e.g. power). */
    record Binary(String path, Fold fold) implements Operation { }

    @FunctionalInterface
    interface Value {
        BigDecimal get(MathContext mc);
    }

    @FunctionalInterface
    interface Function {
        BigDecimal apply(BigDecimal x, MathContext mc);
    }

    @FunctionalInterface
    interface Fold {
        BigDecimal apply(BigDecimal a, BigDecimal b, MathContext mc);
    }
}
