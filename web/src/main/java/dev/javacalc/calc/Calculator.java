package dev.javacalc.calc;

import dev.javacalc.calc.Operation.Binary;
import dev.javacalc.calc.Operation.Constant;
import dev.javacalc.calc.Operation.Unary;
import dev.javacalc.calc.Operation.Variadic;
import dev.javacalc.calc.Outcome.BadOperands;
import dev.javacalc.calc.Outcome.Ok;
import dev.javacalc.calc.Outcome.UnknownOperation;
import dev.javacalc.calc.Outcome.Undefined;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;

import org.springframework.stereotype.Service;

@Service
public class Calculator {

    /** 34 significant digits, ample headroom for a 12-digit display. */
    static final MathContext MC = MathContext.DECIMAL128;

    /** Largest amount of operands we accept in one call. */
    static final int MAX_OPERANDS = 1_000;

    public Outcome evaluate(String name, List<BigDecimal> operands) {
        var operation = Operations.BY_PATH.get(name);
        if (operation == null) {
            return new UnknownOperation(name);
        }
        if (operands == null || operands.stream().anyMatch(java.util.Objects::isNull)) {
            return new BadOperands("'operands' must be a number or a list of numbers");
        }
        if (operands.size() > MAX_OPERANDS) {
            return new BadOperands("At most " + MAX_OPERANDS + " operands are allowed");
        }
        var arityError = switch (operation) {
            case Constant _ -> operands.isEmpty() ? null : "Takes no operands";
            case Variadic _ -> operands.size() >= 2 ? null : "Requires at least 2 operands";
            case Unary _    -> operands.size() == 1 ? null : "Requires exactly 1 operand";
            case Binary _   -> operands.size() == 2 ? null : "Requires exactly 2 operands";
        };
        if (arityError != null) {
            return new BadOperands(arityError);
        }
        try {
            return new Ok(compute(operation, operands));
        } catch (ArithmeticException e) {
            return new Undefined(e.getMessage());
        }
    }

    private static BigDecimal compute(Operation operation, List<BigDecimal> xs) {
        return switch (operation) {
            case Constant(var _, var value) -> value.get(MC);
            case Variadic(var _, var fold) -> xs.stream().reduce((a, b) -> fold.apply(a, b, MC)).orElseThrow();
            case Unary(var _, var function) -> function.apply(xs.getFirst(), MC);
            case Binary(var _, var fold)    -> fold.apply(xs.get(0), xs.get(1), MC);
        };
    }
}
