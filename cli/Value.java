import module java.base;

/// A number. Exact (a fraction of two BigIntegers) for as long as possible, so `1/3x3` is
/// exactly 1 and `0.1+0.2` is exactly 0.3. Once a result is irrational (π, √2, ln 2, ...)
/// it becomes an Approx (a double), and stays one.
///
/// BigInteger never overflows, so exact results can be as large as they need to be.
sealed interface Value permits Value.Exact, Value.Approx {

    /// num/den in lowest terms, with den > 0. The compact constructor normalises every
    /// Exact ever created, so equal values always have equal components.
    record Exact(BigInteger num, BigInteger den) implements Value {
        public Exact {
            if (den.signum() == 0) {
                throw new IllegalArgumentException("zero denominator");
            }
            if (den.signum() < 0) {
                num = num.negate();
                den = den.negate();
            }
            var gcd = num.gcd(den);
            if (!gcd.equals(BigInteger.ONE)) {
                num = num.divide(gcd);
                den = den.divide(gcd);
            }
        }

        boolean isInteger() {
            return den.equals(BigInteger.ONE);
        }

        /// floor(num/den). BigInteger.divide truncates towards zero; mod is never negative.
        BigInteger floorInteger() {
            return num.subtract(num.mod(den)).divide(den);
        }
    }

    record Approx(double value) implements Value { }

    /// Exact results larger than this many bits fall back to a double, so `9^9^9` reports
    /// "result out of range" instead of filling memory.
    int MAX_EXACT_BITS = 100_000;

    static Value of(long n) {
        return new Exact(BigInteger.valueOf(n), BigInteger.ONE);
    }

    /// Wraps a double result, rejecting the values that aren't numbers at all.
    static Value approx(double d) {
        if (Double.isNaN(d)) {
            throw new CalcError.Input("undefined result");
        }
        if (Double.isInfinite(d)) {
            throw new CalcError.Input("result out of range");
        }
        return new Approx(d);
    }

    default double toDouble() {
        return switch (this) {
            case Approx(var d) -> d;
            case Exact(var num, var den) -> {
                double n = num.doubleValue(), d = den.doubleValue();
                // Too big for a double on its own, but the quotient may still fit.
                yield Double.isInfinite(n) || Double.isInfinite(d)
                        ? new BigDecimal(num).divide(new BigDecimal(den), MathContext.DECIMAL128).doubleValue()
                        : n / d;
            }
        };
    }

    default int signum() {
        return switch (this) {
            case Exact e -> e.num().signum();
            case Approx(var d) -> (int) Math.signum(d);
        };
    }

    default Value add(Value other) {
        if (this instanceof Exact(var an, var ad) && other instanceof Exact(var bn, var bd)) {
            return new Exact(an.multiply(bd).add(bn.multiply(ad)), ad.multiply(bd));
        }
        return approx(toDouble() + other.toDouble());
    }

    default Value subtract(Value other) {
        return add(other.negate());
    }

    default Value multiply(Value other) {
        if (this instanceof Exact(var an, var ad) && other instanceof Exact(var bn, var bd)) {
            return new Exact(an.multiply(bn), ad.multiply(bd));
        }
        return approx(toDouble() * other.toDouble());
    }

    default Value divide(Value other) {
        if (other.signum() == 0) {
            throw new CalcError.Input("division by zero");
        }
        if (this instanceof Exact(var an, var ad) && other instanceof Exact(var bn, var bd)) {
            return new Exact(an.multiply(bd), ad.multiply(bn));
        }
        return approx(toDouble() / other.toDouble());
    }

    /// Modulo of floored division, so the result takes the sign of the divisor, as in
    /// Python: `-7 mod 3` is 2, `7 mod -3` is -2.
    default Value mod(Value other) {
        if (other.signum() == 0) {
            throw new CalcError.Input("modulo by zero");
        }
        if (this instanceof Exact && other instanceof Exact) {
            var quotient = (Exact) divide(other);
            return subtract(other.multiply(new Exact(quotient.floorInteger(), BigInteger.ONE)));
        }
        double a = toDouble(), b = other.toDouble(), r = a % b;
        return approx(r != 0 && (r < 0) != (b < 0) ? r + b : r);
    }

    default Value power(Value exponent) {
        if (signum() == 0 && exponent.signum() < 0) {
            throw new CalcError.Input("division by zero");
        }
        if (this instanceof Exact(var num, var den) && exponent instanceof Exact e && e.isInteger()
                && e.num().bitLength() < 32) {
            int n = e.num().intValue(), abs = Math.abs(n);
            if ((long) Math.max(num.bitLength(), den.bitLength()) * abs <= MAX_EXACT_BITS) {
                var result = new Exact(num.pow(abs), den.pow(abs));
                return n < 0 ? of(1).divide(result) : result;
            }
        }
        return approx(Math.pow(toDouble(), exponent.toDouble()));
    }

    /// n! for a whole number n ≥ 0: exact for as long as MAX_EXACT_BITS allows (well past
    /// 1000!), or as a double for an approximate whole number, which overflows after 170!.
    default Value factorial() {
        return switch (this) {
            case Exact e when e.isInteger() && e.num().signum() >= 0 -> {
                if (e.num().bitLength() > 31) {
                    throw new CalcError.Input("result out of range");
                }
                var product = BigInteger.ONE;
                for (int k = 2, n = e.num().intValue(); k <= n; k++) {
                    product = product.multiply(BigInteger.valueOf(k));
                    if (product.bitLength() > MAX_EXACT_BITS) {
                        throw new CalcError.Input("result out of range");
                    }
                }
                yield new Exact(product, BigInteger.ONE);
            }
            case Approx(var d) when d >= 0 && d == Math.rint(d) -> {
                double product = 1;
                for (int k = 2; k <= Math.min(d, 171); k++) {
                    product *= k;
                }
                yield approx(product);
            }
            default -> throw new CalcError.Input("factorial needs a whole number ≥ 0");
        };
    }

    default Value negate() {
        return switch (this) {
            case Exact(var num, var den) -> new Exact(num.negate(), den);
            case Approx(var d) -> new Approx(-d);
        };
    }

    default Value sqrt() {
        if (signum() < 0) {
            throw new CalcError.Input("square root of a negative number");
        }
        // Perfect squares stay exact: √(9/4) is exactly 3/2.
        if (this instanceof Exact(var num, var den)) {
            var n = num.sqrt();
            var d = den.sqrt();
            if (n.multiply(n).equals(num) && d.multiply(d).equals(den)) {
                return new Exact(n, d);
            }
        }
        return approx(Math.sqrt(toDouble()));
    }

    default Value abs() {
        return signum() < 0 ? negate() : this;
    }

    default Value floor() {
        return switch (this) {
            case Exact e -> new Exact(e.floorInteger(), BigInteger.ONE);
            case Approx(var d) -> approx(Math.floor(d));
        };
    }

    default Value ceil() {
        return negate().floor().negate();
    }

    /// Rounds half away from zero, as spreadsheets do: 2.5 → 3, -2.5 → -3.
    default Value round() {
        return switch (this) {
            case Exact(var num, var den) -> {
                var twice = num.abs().shiftLeft(1).add(den);
                var rounded = twice.divide(den.shiftLeft(1));
                yield new Exact(num.signum() < 0 ? rounded.negate() : rounded, BigInteger.ONE);
            }
            case Approx(var d) -> approx(new BigDecimal(d).setScale(0, RoundingMode.HALF_UP).doubleValue());
        };
    }

    /// A total order for sorting (min, max, median, mode): exact when both values are exact.
    static int compare(Value a, Value b) {
        if (a instanceof Exact(var an, var ad) && b instanceof Exact(var bn, var bd)) {
            return an.multiply(bd).compareTo(bn.multiply(ad));
        }
        return Double.compare(a.toDouble(), b.toDouble());
    }
}
