package dev.javacalc.calc;

import dev.javacalc.calc.Operation.Binary;
import dev.javacalc.calc.Operation.Constant;
import dev.javacalc.calc.Operation.Unary;
import dev.javacalc.calc.Operation.Variadic;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** The catalogue of supported operations. Every one is pure BigDecimal arithmetic. */
public final class Operations {

    private Operations() { }

    /** Pi to 34 significant digits, the precision of MathContext.DECIMAL128. */
    private static final BigDecimal PI = new BigDecimal("3.141592653589793238462643383279503");

    private static final List<Operation> ALL = List.of(
            new Variadic("add",      (a, b, mc) -> a.add(b, mc)),
            new Variadic("subtract", (a, b, mc) -> a.subtract(b, mc)),
            new Variadic("multiply", (a, b, mc) -> a.multiply(b, mc)),
            new Variadic("divide",   Operations::divide),
            new Unary("sqroot",      Operations::sqroot),
            new Unary("square",      (x, mc) -> x.multiply(x, mc)),
            new Unary("inverse",     (x, mc) -> divide(BigDecimal.ONE, x, mc)),
            new Binary("power",      Operations::power),
            new Constant("pi",       mc -> PI.round(mc)));


    /** Operations keyed by path, in declaration order. */
    public static final Map<String, Operation> BY_PATH = ALL.stream()
            .collect(Collectors.toMap(Operation::path, Function.identity(), (a, _) -> a, LinkedHashMap::new));

    private static BigDecimal divide(BigDecimal a, BigDecimal b, MathContext mc) {
        if (b.signum() == 0) {
            throw new ArithmeticException("Division by zero");
        }
        return a.divide(b, mc);
    }

    private static BigDecimal sqroot(BigDecimal x, MathContext mc) {
        if (x.signum() < 0) {
            throw new ArithmeticException("Square root of a negative number");
        }
        return x.sqrt(mc);
    }

    private static BigDecimal power(BigDecimal base, BigDecimal exponent, MathContext mc) {
        if (exponent.signum() == 0) {
            return BigDecimal.ONE;
        }
        if (base.signum() == 0) {
            if (exponent.signum() < 0) {
                throw new ArithmeticException("Zero cannot be raised to a negative power");
            }
            return BigDecimal.ZERO;
        }
        // Integral exponents are computed exactly (to the MathContext) with BigDecimal.pow.
        var stripped = exponent.stripTrailingZeros();
        if (stripped.scale() <= 0 && stripped.abs().compareTo(BigDecimal.valueOf(999_999_999)) <= 0) {
            return base.pow(stripped.intValueExact(), mc);
        }
        // Fractional exponents have no BigDecimal implementation; fall back to double precision.
        if (base.signum() < 0) {
            throw new ArithmeticException("Negative base with a fractional exponent");
        }
        double d = Math.pow(base.doubleValue(), exponent.doubleValue());
        if (!Double.isFinite(d)) {
            throw new ArithmeticException("Result is out of range");
        }
        return BigDecimal.valueOf(d).round(mc);
    }
}
