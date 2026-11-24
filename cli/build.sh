#!/usr/bin/env bash
# Builds the fast version of the CLI: a precompiled jar plus an AOT cache.
#
#   build/calc.jar  the compiled classes (plain javac + jar: no Maven needed). Its manifest
#                   enables native access, for isatty and locale detection (see Native.java)
#   build/calc.aot  an AOT cache (JDK 25): classes pre-loaded and pre-linked, and
#                   method profiles, recorded from a training run
#
# Run it with ./jcalc. Rebuild after editing the sources or upgrading the JDK; the cache is
# tied to this exact jar and JDK build.
set -euo pipefail
cd "$(dirname "$0")"

rm -rf build
mkdir -p build/classes

echo "Compiling..."
javac -d build/classes ./*.java
printf 'Main-Class: Calc\nEnable-Native-Access: ALL-UNNAMED\n' > build/MANIFEST.MF
jar --create --file build/calc.jar --manifest build/MANIFEST.MF -C build/classes .
rm -rf build/classes build/MANIFEST.MF

# One run that exercises most of the code: stdin, lists, ranges, functions, √, π, powers,
# mod, factorial, trig, decimals, --pretty output and list output. ASCII spellings only (no °),
# so the build works in any locale. Train with the same flags ./jcalc runs with.
echo "Training the AOT cache..."
training="x2 + sqrt[sum 1..8 + prod 3..4] + avg{3,4,8} + 2pi + 7 mod 3 + 2^0.5 + 3^2 + median{3 1 2}
          + mode{1 2 2} + stdev{2 4 4 4 5 5 7 9} + round{2.5} + floor{7/2} + ln{2} + sin{pi/6} + 3! - 1/3 + 1.5"
if ! log=$(printf '1\n2\n3\n' | java -XX:AOTCacheOutput=build/calc.aot -XX:TieredStopAtLevel=1 \
             -jar build/calc.jar --pretty "$training" 2>&1); then
  echo "$log" >&2
  echo "AOT training failed; ./jcalc will still work, just without the cache." >&2
  exit 1
fi

echo "Built:"
ls -lh build/calc.jar build/calc.aot | awk '{print "  " $NF " (" $5 ")"}'
