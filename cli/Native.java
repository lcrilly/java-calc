import module java.base;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/// Calls into the C library and macOS frameworks with the Foreign Function & Memory API
/// (JDK 22), for the few things the JDK can't tell us:
///
///  - whether stdin or stdout is a terminal (`isatty`);
///  - the C locale's number format, including Indian-style grouping (`localeconv`);
///  - the macOS Language & Region number format, including custom symbols (CoreFoundation).
///
/// Calling native code is a restricted operation. The built jar enables it in its manifest;
/// when running from source, pass `--enable-native-access=ALL-UNNAMED`. Without it every
/// method here returns empty and callers fall back to pure Java, so the JVM never prints
/// its "restricted method" warning.
final class Native {

    private Native() { }

    private static final boolean MAC = System.getProperty("os.name", "").startsWith("Mac");

    static boolean enabled() {
        return Native.class.getModule().isNativeAccessEnabled();
    }

    private static MethodHandle libc(String name, FunctionDescriptor descriptor) {
        var linker = Linker.nativeLinker();
        return linker.downcallHandle(linker.defaultLookup().find(name).orElseThrow(), descriptor);
    }

    /// Linking a downcall handle is the expensive part of a native call, so do it once, and
    /// only on first use (a holder class is initialised when it is first touched).
    private static final class Isatty {
        static final MethodHandle HANDLE = libc("isatty", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
    }

    /// Whether file descriptor `fd` (0 stdin, 1 stdout) is a terminal.
    static Optional<Boolean> isTerminal(int fd) {
        if (!enabled()) {
            return Optional.empty();
        }
        try {
            int result = (int) Isatty.HANDLE.invokeExact(fd);
            return Optional.of(result == 1);
        } catch (Throwable e) {
            return Optional.empty();
        }
    }

    /// The C library's LC_NUMERIC format (honouring LC_ALL, LC_NUMERIC and LANG), read from
    /// the first three fields of `struct lconv`: decimal_point, thousands_sep and grouping.
    static Optional<NumberStyle> posixNumberStyle() {
        if (!enabled()) {
            return Optional.empty();
        }
        int lcNumeric = MAC ? 4 : 1; // <locale.h>: 4 on macOS and the BSDs, 1 on glibc and musl
        var setlocale = libc("setlocale", FunctionDescriptor.of(ADDRESS, JAVA_INT, ADDRESS));
        var localeconv = libc("localeconv", FunctionDescriptor.of(ADDRESS));
        try (var arena = Arena.ofConfined()) {
            var selected = (MemorySegment) setlocale.invokeExact(lcNumeric, arena.allocateFrom(""));
            if (selected.equals(MemorySegment.NULL)) {
                return Optional.empty();
            }
            try {
                var lconv = ((MemorySegment) localeconv.invokeExact()).reinterpret(3 * ADDRESS.byteSize());
                var decimalPoint = cString(lconv.get(ADDRESS, 0));
                var thousandsSep = cString(lconv.get(ADDRESS, ADDRESS.byteSize()));
                var grouping = cBytes(lconv.get(ADDRESS, 2 * ADDRESS.byteSize()));
                // Group sizes: each byte is one group, from the decimal mark outwards.
                int primary = grouping.length > 0 && grouping[0] > 0 && grouping[0] < 127 ? grouping[0] : 0;
                int secondary = grouping.length > 1 && grouping[1] > 0 && grouping[1] < 127 ? grouping[1] : primary;
                char decimal = decimalPoint.isEmpty() ? '.' : decimalPoint.charAt(0);
                Character group = primary > 0 && !thousandsSep.isEmpty() ? thousandsSep.charAt(0) : null;
                return Optional.of(new NumberStyle(decimal, group, primary > 0 ? primary : 3, primary > 0 ? secondary : 3));
            } finally {
                // Back to the C locale, so nothing else in the process changes behaviour.
                var ignored = (MemorySegment) setlocale.invokeExact(lcNumeric, arena.allocateFrom("C"));
            }
        } catch (Throwable e) {
            return Optional.empty();
        }
    }

    /// The number format from System Settings → Language & Region, including any custom
    /// symbols, found by formatting a sample number with Core Foundation. Empty off macOS.
    static Optional<String> macNumberSample() {
        if (!MAC || !enabled()) {
            return Optional.empty();
        }
        final long decimalStyle = 1;          // kCFNumberFormatterDecimalStyle
        final long doubleType = 13;           // kCFNumberDoubleType
        final int utf8 = 0x0800_0100;         // kCFStringEncodingUTF8
        try (var arena = Arena.ofConfined()) {
            var linker = Linker.nativeLinker();
            // A name, not a Path: since macOS 11 system frameworks live in the dyld shared
            // cache, so the file doesn't exist on disk, but dlopen still finds it.
            var cf = SymbolLookup.libraryLookup("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation", arena);
            Function<String, MemorySegment> symbol = name -> cf.find(name).orElseThrow();
            var copyCurrentLocale = linker.downcallHandle(symbol.apply("CFLocaleCopyCurrent"), FunctionDescriptor.of(ADDRESS));
            var createFormatter = linker.downcallHandle(symbol.apply("CFNumberFormatterCreate"),
                    FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG));
            var formatNumber = linker.downcallHandle(symbol.apply("CFNumberFormatterCreateStringWithValue"),
                    FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS));
            var getCString = linker.downcallHandle(symbol.apply("CFStringGetCString"),
                    FunctionDescriptor.of(JAVA_BYTE, ADDRESS, ADDRESS, JAVA_LONG, JAVA_INT));
            var release = linker.downcallHandle(symbol.apply("CFRelease"), FunctionDescriptor.ofVoid(ADDRESS));

            var locale = (MemorySegment) copyCurrentLocale.invokeExact();
            if (locale.equals(MemorySegment.NULL)) {
                return Optional.empty();
            }
            var formatter = (MemorySegment) createFormatter.invokeExact(MemorySegment.NULL, locale, decimalStyle);
            release.invokeExact(locale);
            if (formatter.equals(MemorySegment.NULL)) {
                return Optional.empty();
            }
            var value = arena.allocateFrom(JAVA_DOUBLE, 1234567.5);
            var string = (MemorySegment) formatNumber.invokeExact(MemorySegment.NULL, formatter, doubleType, value);
            release.invokeExact(formatter);
            if (string.equals(MemorySegment.NULL)) {
                return Optional.empty();
            }
            var buffer = arena.allocate(64);
            byte ok = (byte) getCString.invokeExact(string, buffer, 64L, utf8);
            release.invokeExact(string);
            return ok == 0 ? Optional.empty() : Optional.of(buffer.getString(0));
        } catch (Throwable e) {
            return Optional.empty();
        }
    }

    private static String cString(MemorySegment pointer) {
        return pointer.equals(MemorySegment.NULL) ? "" : pointer.reinterpret(Long.MAX_VALUE).getString(0);
    }

    private static byte[] cBytes(MemorySegment pointer) {
        if (pointer.equals(MemorySegment.NULL)) {
            return new byte[0];
        }
        var bytes = pointer.reinterpret(Long.MAX_VALUE);
        int n = 0;
        while (bytes.get(JAVA_BYTE, n) != 0) {
            n++;
        }
        return bytes.asSlice(0, n).toArray(JAVA_BYTE);
    }
}
