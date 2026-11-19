package dev.javacalc.calc;

import static org.assertj.core.api.Assertions.assertThat;

import dev.javacalc.calc.Outcome.BadOperands;
import dev.javacalc.calc.Outcome.Ok;
import dev.javacalc.calc.Outcome.Undefined;
import dev.javacalc.calc.Outcome.UnknownOperation;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class CalculatorTest {

    private final Calculator calculator = new Calculator();

    private static List<BigDecimal> nums(String... xs) {
        return Stream.of(xs).map(BigDecimal::new).toList();
    }

    static Stream<Arguments> successes() {
        return Stream.of(
                Arguments.of("add", nums("5", "10", "10"), "25"),
                Arguments.of("add", nums("0.1", "0.2"), "0.3"),          // exact, unlike double
                Arguments.of("subtract", nums("100", "1", "2", "3"), "94"),
                Arguments.of("multiply", nums("2", "3", "4"), "24"),
                Arguments.of("multiply", nums("1.5", "-2"), "-3"),
                Arguments.of("divide", nums("100", "5", "2"), "10"),
                Arguments.of("divide", nums("1", "3"), "0.3333333333333333333333333333333333"),
                Arguments.of("sqroot", nums("81"), "9"),
                Arguments.of("sqroot", nums("2"), "1.414213562373095048801688724209698"),
                Arguments.of("square", nums("-1.1"), "1.21"),
                Arguments.of("inverse", nums("8"), "0.125"),
                Arguments.of("power", nums("2", "10"), "1024"),
                Arguments.of("power", nums("2", "-2"), "0.25"),
                Arguments.of("power", nums("9", "0.5"), "3"),
                Arguments.of("power", nums("0", "0"), "1"),
                Arguments.of("pi", nums(), "3.141592653589793238462643383279503"));
    }

    @ParameterizedTest(name = "{0}{1} = {2}")
    @MethodSource("successes")
    void computes(String op, List<BigDecimal> operands, String expected) {
        var outcome = calculator.evaluate(op, operands);
        assertThat(outcome).isInstanceOfSatisfying(Ok.class,
                ok -> assertThat(ok.value().stripTrailingZeros().toPlainString()).isEqualTo(expected));
    }

    static Stream<Arguments> undefined() {
        return Stream.of(
                Arguments.of("divide", nums("1", "0")),
                Arguments.of("divide", nums("1", "2", "0")),
                Arguments.of("inverse", nums("0")),
                Arguments.of("sqroot", nums("-4")),
                Arguments.of("power", nums("0", "-1")),
                Arguments.of("power", nums("-8", "0.5")));
    }

    @ParameterizedTest
    @MethodSource("undefined")
    void rejectsUndefinedMath(String op, List<BigDecimal> operands) {
        assertThat(calculator.evaluate(op, operands)).isInstanceOf(Undefined.class);
    }

    @Test
    void enforcesArity() {
        assertThat(calculator.evaluate("add", nums("1"))).isInstanceOf(BadOperands.class);
        assertThat(calculator.evaluate("sqroot", nums("1", "2"))).isInstanceOf(BadOperands.class);
        assertThat(calculator.evaluate("power", nums("1", "2", "3"))).isInstanceOf(BadOperands.class);
        assertThat(calculator.evaluate("pi", nums("1"))).isInstanceOf(BadOperands.class);
        assertThat(calculator.evaluate("add", null)).isInstanceOf(BadOperands.class);
    }

    @Test
    void unknownOperation() {
        assertThat(calculator.evaluate("sine", nums("1"))).isEqualTo(new UnknownOperation("sine"));
    }
}
