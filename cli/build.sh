#!/usr/bin/env bash
# Builds the fast version of the CLI: a precompiled jar plus an AOT cache.
#
#   build/calc.jar  the compiled classes (plain javac + jar: no Maven needed)
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
jar --create --file build/calc.jar --main-class Calc -C build/classes .
rm -rf build/classes

# One run that exercises most of the code: lists, ranges, functions, √, π, powers, mod,
# decimals and list output. ASCII spellings, so the build works in any locale. Train with the
# same flags ./jcalc runs with.
echo "Training the AOT cache..."
training="{1 2 3}x2 + sqrt[sum 1..8 + prod 3..4] + avg{3,4,8} + 2pi + 7 mod 3 + 2^0.5 + 3^2
          + median{3 1 2} + stdev{2 4 4 4 5 5 7 9} + round{2.5} + floor{7/2} + ln{2} + sin{1} - 1/3 + 1.5"
if ! log=$(java -XX:AOTCacheOutput=build/calc.aot -XX:TieredStopAtLevel=1 -jar build/calc.jar "$training" 2>&1); then
  echo "$log" >&2
  echo "AOT training failed; ./jcalc will still work, just without the cache." >&2
  exit 1
fi

echo "Built:"
ls -lh build/calc.jar build/calc.aot | awk '{print "  " $NF " (" $5 ")"}'
