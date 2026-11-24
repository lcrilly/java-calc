/// Errors remember where in the input they happened, so they can be shown in place:
///
///     calc: missing '}'
///       2x{3+4
///         ^
///
/// Sealed, so the exit code is decided by an exhaustive switch over the two kinds.
abstract sealed class CalcError extends RuntimeException permits CalcError.Usage, CalcError.Input {

    /// Code-point offsets into the input, or -1 when the error isn't tied to a place.
    private int start = -1;
    private int end = -1;

    CalcError(String message) {
        // No stack trace: these are expected outcomes, not bugs, and capturing one is slow.
        super(message, null, false, false);
    }

    /// The command line itself is wrong (unknown option, nothing to read, ...): exit status 2.
    static final class Usage extends CalcError {
        Usage(String message) {
            super(message);
        }
    }

    /// An expression or its input is wrong (syntax, division by zero, ...): exit status 1.
    static final class Input extends CalcError {
        Input(String message) {
            super(message);
        }
    }

    /// Points the error at [start, end), unless something more specific already has. Nodes
    /// call this as an error passes up the tree, so the innermost failing part is shown.
    CalcError at(int start, int end) {
        if (this.start < 0) {
            this.start = start;
            this.end = end;
        }
        return this;
    }

    int exitCode() {
        return switch (this) {
            case Usage _ -> 2;
            case Input _ -> 1;
        };
    }

    /// The message, followed by `source` with the error's place underlined.
    String inSource(String source) {
        var text = source.stripTrailing();
        if (start < 0 || text.isEmpty()) {
            return getMessage();
        }
        return getMessage() + "\n  " + text + "\n  " + " ".repeat(start) + "^".repeat(Math.max(1, end - start));
    }
}
