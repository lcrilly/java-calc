/// Everything that can go wrong evaluating an expression. Sealed, so `Calc.java` can
/// map each kind to its exit code with an exhaustive switch.
abstract sealed class CalcError extends RuntimeException permits CalcError.Syntax, CalcError.Maths {

    CalcError(String message) {
        super(message);
    }

    /// The input can't be read as an expression. `position` is a code-point index into the
    /// input, used to draw a caret under the problem.
    static final class Syntax extends CalcError {
        final int position;

        Syntax(String message, int position) {
            super(message);
            this.position = position;
        }
    }

    /// The expression is well formed but has no answer: division by zero, √ of a negative, ...
    static final class Maths extends CalcError {
        Maths(String message) {
            super(message);
        }
    }
}
