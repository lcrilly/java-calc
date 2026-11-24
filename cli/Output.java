import module java.base;

/// Turns values into decimals for printing.
///
///  - Integers print as integers: `7/7` → `1`.
///  - Fractions that terminate print in full: `1/64` → `0.015625`.
///  - Recurring and irrational results print to 5 decimal places, trailing zeros trimmed,
///    or to `-p N` places.
///  - Approximate results of 1e16 or more use scientific notation, since their low digits
///    aren't real.
final class Output {

    private Output() { }

    sealed interface Precision {
        /// Terminating fractions in full; anything else to DEFAULT_PLACES.
        record Default() implements Precision { }
        record Places(int n) implements Precision { }
        /// As many digits as a double holds (its shortest round-trip form).
        record Max() implements Precision { }
    }

    static final int DEFAULT_PLACES = 5;

    private static final double SCIENTIFIC_THRESHOLD = 1e16;

    /// A number ready to print: its sign, the digits either side of the decimal mark, and an
    /// optional exponent. Printing it plainly (`toString`) or in a local style
    /// (`NumberStyle.apply`) is then only a matter of punctuation.
    record Decimal(boolean negative, String integer, String fraction, Integer exponent) {

        /// Reads a plain decimal string such as `-1234.50`, trimming trailing zeros and the
        /// sign from zero.
        static Decimal of(String plain, Integer exponent) {
            boolean negative = plain.startsWith("-");
            var digits = negative ? plain.substring(1) : plain;
            int point = digits.indexOf('.');
            var integer = point < 0 ? digits : digits.substring(0, point);
            var fraction = point < 0 ? "" : digits.substring(point + 1).replaceAll("0+$", "");
            boolean zero = fraction.isEmpty() && integer.chars().allMatch(c -> c == '0');
            return new Decimal(negative && !zero, integer, fraction, exponent);
        }

        @Override
        public String toString() {
            return (negative ? "-" : "") + integer + (fraction.isEmpty() ? "" : "." + fraction)
                    + (exponent == null ? "" : "e" + exponent);
        }
    }

    static Decimal format(Value value, Precision precision) {
        return switch (value) {
            case Value.Exact exact -> exact(exact, precision);
            case Value.Approx(var d) -> real(d, precision);
        };
    }

    private static Decimal exact(Value.Exact r, Precision precision) {
        if (r.isInteger()) {
            return Decimal.of(r.num().toString(), null);
        }
        OptionalInt terminating = terminatingPlaces(r.den());
        Integer places = switch (precision) {
            case Precision.Places(var n) -> n;
            case Precision.Default _ -> terminating.orElse(DEFAULT_PLACES);
            case Precision.Max _ -> terminating.isPresent() ? terminating.getAsInt() : null;
        };
        if (places == null) {
            return real(r.toDouble(), precision); // recurring, at max precision: show a double's worth
        }
        // Exact long division, rounding half away from zero.
        return Decimal.of(new BigDecimal(r.num()).divide(new BigDecimal(r.den()), places, RoundingMode.HALF_UP)
                .toPlainString(), null);
    }

    /// Decimal places needed if 1/den terminates (den = 2^a·5^b), else empty.
    private static OptionalInt terminatingPlaces(BigInteger den) {
        int twos = den.getLowestSetBit();
        var rest = den.shiftRight(twos);
        int fives = 0;
        var five = BigInteger.valueOf(5);
        while (rest.mod(five).signum() == 0) {
            rest = rest.divide(five);
            fives++;
        }
        return rest.equals(BigInteger.ONE) ? OptionalInt.of(Math.max(twos, fives)) : OptionalInt.empty();
    }

    private static Decimal real(double d, Precision precision) {
        if (Math.abs(d) >= SCIENTIFIC_THRESHOLD) {
            return scientific(d, precision);
        }
        var text = switch (precision) {
            // new BigDecimal(double) is the double's exact binary value, so rounding it gives
            // the correctly rounded decimal: the same digits as Rust's {:.5}.
            case Precision.Default _ -> new BigDecimal(d).setScale(DEFAULT_PLACES, RoundingMode.HALF_EVEN).toPlainString();
            case Precision.Places(var n) -> new BigDecimal(d).setScale(n, RoundingMode.HALF_EVEN).toPlainString();
            // Double.toString is the shortest string that reads back as the same double.
            case Precision.Max _ -> new BigDecimal(Double.toString(d)).toPlainString();
        };
        return Decimal.of(text, null);
    }

    private static Decimal scientific(double d, Precision precision) {
        var digits = switch (precision) {
            case Precision.Default _ -> new BigDecimal(d).round(new MathContext(DEFAULT_PLACES + 1, RoundingMode.HALF_EVEN));
            case Precision.Places(var n) -> new BigDecimal(d).round(new MathContext(n + 1, RoundingMode.HALF_EVEN));
            case Precision.Max _ -> new BigDecimal(Double.toString(d));
        };
        int exponent = digits.precision() - digits.scale() - 1;
        return Decimal.of(digits.movePointLeft(exponent).toPlainString(), exponent);
    }
}
