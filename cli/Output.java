import module java.base;

/// Formats results for printing.
///
///  - Integers print as integers: `7/7` → `1`.
///  - Fractions that terminate print in full: `1/64` → `0.015625`.
///  - Recurring and irrational results print to 5 decimal places, trailing zeros trimmed,
///    or to `-p N` places.
///  - A decimal point anywhere in the input keeps one in the output: `2.5x2` → `5.0`.
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

    static String format(Value value, boolean decimalInput, Precision precision) {
        var text = switch (value) {
            case Value.Exact exact -> exact(exact, precision);
            case Value.Approx(var d) -> real(d, precision);
        };
        return tidy(text, decimalInput);
    }

    private static String exact(Value.Exact r, Precision precision) {
        if (r.isInteger()) {
            return r.num().toString();
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
        return new BigDecimal(r.num()).divide(new BigDecimal(r.den()), places, RoundingMode.HALF_UP).toPlainString();
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

    private static String real(double d, Precision precision) {
        if (Math.abs(d) >= SCIENTIFIC_THRESHOLD) {
            return scientific(d, precision);
        }
        return switch (precision) {
            // new BigDecimal(double) is the double's exact binary value, so rounding it gives
            // the correctly rounded decimal, the same digits as Rust's {:.5}.
            case Precision.Default _ -> new BigDecimal(d).setScale(DEFAULT_PLACES, RoundingMode.HALF_EVEN).toPlainString();
            case Precision.Places(var n) -> new BigDecimal(d).setScale(n, RoundingMode.HALF_EVEN).toPlainString();
            // Double.toString is the shortest string that reads back as the same double.
            case Precision.Max _ -> new BigDecimal(Double.toString(d)).toPlainString();
        };
    }

    private static String scientific(double d, Precision precision) {
        var digits = switch (precision) {
            case Precision.Default _ -> new BigDecimal(d).round(new MathContext(DEFAULT_PLACES + 1, RoundingMode.HALF_EVEN));
            case Precision.Places(var n) -> new BigDecimal(d).round(new MathContext(n + 1, RoundingMode.HALF_EVEN));
            case Precision.Max _ -> new BigDecimal(Double.toString(d));
        };
        int exponent = digits.precision() - digits.scale() - 1;
        var mantissa = digits.movePointLeft(exponent);
        if (!(precision instanceof Precision.Max)) {
            int places = precision instanceof Precision.Places(var n) ? n : DEFAULT_PLACES;
            mantissa = mantissa.setScale(places, RoundingMode.HALF_EVEN);
        }
        return tidy(mantissa.toPlainString(), false) + "e" + exponent;
    }

    /// Trims trailing fractional zeros, turns "-0" into "0", and keeps a ".0" when the input
    /// itself had decimals.
    private static String tidy(String s, boolean decimalInput) {
        if (s.contains("e")) {
            return s;
        }
        if (s.contains(".")) {
            s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        if (s.equals("-0")) {
            s = "0";
        }
        if (decimalInput && !s.contains(".")) {
            s += ".0";
        }
        return s;
    }
}
