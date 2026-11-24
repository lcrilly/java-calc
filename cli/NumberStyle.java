import module java.base;

/// How numbers are written for people: the decimal mark and digit grouping.
///
/// The style only changes how numbers are displayed (and read, under an explicit --pretty).
/// Plain input is the same everywhere, so a script gives the same answer on every machine.
///
/// @param group     the group separator, or null for no grouping
/// @param primary   size of the group nearest the decimal mark (3 almost everywhere)
/// @param secondary size of every group after that (2 in en_IN: 12,34,567)
record NumberStyle(char decimal, Character group, int primary, int secondary) {

    static final NumberStyle PLAIN = new NumberStyle('.', null, 3, 3);

    /// Reads a style from an example such as `1,234.5`, `1.234,5`, `1 234,5` or `12,34,567.8`.
    /// The last separator is the decimal mark, so the example must have a fractional part.
    static NumberStyle fromExample(String example) {
        var invalid = new CalcError.Usage("invalid number format '" + example
                + "' (give an example with decimals, such as 1,234.5 or 1.234,5)");
        int[] cs = example.codePoints().toArray();
        int mark = cs.length - 1;
        while (mark >= 0 && Character.isDigit(cs[mark]) && cs[mark] < 128) {
            mark--;
        }
        if (mark <= 0 || mark == cs.length - 1 || cs[mark] > Character.MAX_VALUE) {
            throw invalid;
        }
        char decimal = (char) cs[mark];
        var groups = new ArrayList<Integer>(List.of(0));
        Character group = null;
        for (int i = 0; i < mark; i++) {
            int c = cs[i];
            if (c >= '0' && c <= '9') {
                groups.set(groups.size() - 1, groups.getLast() + 1);
            } else if ((group == null || group == c) && c != decimal && c <= Character.MAX_VALUE) {
                group = (char) c;
                groups.add(0);
            } else {
                throw invalid;
            }
        }
        if (groups.contains(0)) {
            throw invalid;
        }
        if (group == null) {
            return new NumberStyle(decimal, null, 3, 3);
        }
        int primary = groups.getLast();
        int secondary = groups.size() >= 3 ? groups.get(groups.size() - 2) : primary;
        return new NumberStyle(decimal, group, primary, secondary);
    }

    /// Writes a decimal in this style.
    String apply(Output.Decimal d) {
        var out = new StringBuilder();
        if (d.negative()) {
            out.append('-');
        }
        // Grouping a mantissa (1.5e20) would mean nothing.
        out.append(group != null && d.exponent() == null ? groupDigits(d.integer()) : d.integer());
        if (!d.fraction().isEmpty()) {
            out.append(decimal).append(d.fraction());
        }
        if (d.exponent() != null) {
            out.append('e').append(d.exponent());
        }
        return out.toString();
    }

    private String groupDigits(String integer) {
        var out = new StringBuilder(integer);
        int at = integer.length() - primary;
        while (at > 0) {
            out.insert(at, group.charValue());
            at -= secondary;
        }
        return out.toString();
    }

    /// True when `n` digits after a group separator make a valid group.
    boolean isGroupSize(int n) {
        return n == primary || n == secondary;
    }

    /// The user's preferred style, detected once, in order of precedence:
    /// `CALC_NUMBER_FORMAT` (an example), `LC_ALL`/`LC_NUMERIC` when set, the macOS
    /// Language & Region settings, then `LANG`.
    static NumberStyle detected() {
        return Detected.STYLE;
    }

    /// Lazy holder: the detection runs on first use only, and only once.
    private static final class Detected {
        static final NumberStyle STYLE = detect();
    }

    private static NumberStyle detect() {
        var example = System.getenv("CALC_NUMBER_FORMAT");
        if (example != null) {
            try {
                return fromExample(example);
            } catch (CalcError _) {
                // fall through to the locale
            }
        }
        if (!isSet("LC_ALL") && !isSet("LC_NUMERIC")) {
            var system = Native.macNumberSample().flatMap(NumberStyle::tryExample);
            if (system.isPresent()) {
                return system.get();
            }
        }
        return Native.posixNumberStyle().orElseGet(NumberStyle::fromJavaLocale);
    }

    private static Optional<NumberStyle> tryExample(String example) {
        try {
            return Optional.of(fromExample(example));
        } catch (CalcError _) {
            return Optional.empty();
        }
    }

    private static boolean isSet(String name) {
        var value = System.getenv(name);
        return value != null && !value.isEmpty();
    }

    /// Without native access: the style of the locale named by LC_ALL, LC_NUMERIC or LANG,
    /// from the JDK's own (CLDR) locale data. Java's DecimalFormat has a single grouping
    /// size, so Indian-style secondary groups (12,34,567) can't be detected this way.
    private static NumberStyle fromJavaLocale() {
        var name = Stream.of("LC_ALL", "LC_NUMERIC", "LANG").filter(NumberStyle::isSet)
                .map(System::getenv).findFirst().orElse("C");
        var base = name.split("[.@]")[0];
        if (base.equals("C") || base.equals("POSIX") || base.isEmpty()) {
            return PLAIN;
        }
        var locale = Locale.forLanguageTag(base.replace('_', '-'));
        var symbols = DecimalFormatSymbols.getInstance(locale);
        int size = java.text.NumberFormat.getNumberInstance(locale) instanceof DecimalFormat f ? f.getGroupingSize() : 3;
        return size > 0
                ? new NumberStyle(symbols.getDecimalSeparator(), symbols.getGroupingSeparator(), size, size)
                : new NumberStyle(symbols.getDecimalSeparator(), null, 3, 3);
    }
}
