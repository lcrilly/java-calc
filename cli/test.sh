#!/usr/bin/env bash
# End-to-end tests for the command-line calculator: runs `java Calc.java` exactly as a user
# would. Most cases are ported from calc-cli (https://github.com/lcrilly/calc-cli), the Rust
# tool this mirrors. Run from anywhere: cli/test.sh
set -u
cd "$(dirname "$0")"

# Unicode arguments (π, √, ²) need a UTF-8 locale; the C-locale behaviour is tested at the end.
[[ $(locale charmap 2>/dev/null) == UTF-8 ]] || export LC_ALL=C.UTF-8

pass=0 fail=0
errfile=$(mktemp)
trap 'rm -f "$errfile"' EXIT

# t <exit> <stdout> <stderr substring> -- <args...>
# In the expected stdout, spaces stand for newlines: a list prints one number per line.
t() {
  local want_exit=$1 want_out=${2// /$'\n'} want_err=$3; shift 4
  local out err code
  out=$(java Calc.java "$@" 2>"$errfile"); code=$?
  err=$(grep -v '^Picked up' "$errfile")   # the JVM's JAVA_TOOL_OPTIONS notice, if set
  if [[ $code == "$want_exit" && $out == "$want_out" && $err == *"$want_err"* ]]; then
    pass=$((pass + 1)); printf 'PASS  %-34s -> %s\n' "$*" "$(echo ${out:-$err} | head -c 70)"
  else
    fail=$((fail + 1)); printf 'FAIL  %-34s exit=%s out=%q err=%q\n      want exit=%s out=%q err~%q\n' \
      "$*" "$code" "$out" "$err" "$want_exit" "$want_out" "$want_err"
  fi
}

MACRON=$(printf 'x\xcc\x84')     # x̄ : x + U+0304 combining macron
OVERLINE=$(printf 'x\xcc\x85')   # x̅ : x + U+0305 combining overline

echo "== arithmetic and precedence"
t 0 3 ""            -- "1+2"
t 0 3 ""            -- 1 + 2
t 0 7 ""            -- "1+2*3"
t 0 5 ""            -- "8-2-1"
t 0 5 ""            -- "100/10/2"
t 0 5.5 ""          -- "2 * 3 - 4 / 8"
t 0 14 ""           -- "2x{3+4}"
t 0 14 ""           -- 2 x "{3" + "4}"
t 0 7 ""            -- "5 - -2"
t 0 3 ""            -- -- "--3"
t 2 "" "unknown option '--3'" -- "--3"
t 0 -4 ""           -- "1-5"
t 0 -6 ""           -- "2*-3"
t 0 -3.14159 ""     -- "-pi"

echo "== exact numbers"
t 0 0.3 ""          -- "0.1+0.2"
t 0 0.75 ""         -- ".5+.25"
t 0 1 ""            -- "7/7"
t 0 1 ""            -- "1/3x3"
t 0 0.015625 ""     -- "1/64"
t 0 0.33333 ""      -- "1/3"
t 0 0.66667 ""      -- "2/3"
t 0 5.0 ""          -- "2.5x2"
t 0 2.2 ""          -- "1.10*2"
t 0 9999999999999999999800000000000000000001 "" -- "99999999999999999999*99999999999999999999"
t 0 515377520732011331036461129765621272702107522001 "" -- "3^100"
t 0 265252859812191058636308480000000 "" -- "product 1..30"
t 0 3000 ""         -- "1_000x3"
t 0 1234565 ""      -- "1'234'565"

echo "== operators"
t 0 144 ""          -- "{3x4}^2"
t 0 3 ""            -- "(99 - {36÷3})%4"
t 0 2 ""            -- "-7 mod 3"
t 0 -2 ""           -- "7%-3"
t 0 1.5 ""          -- "5.5 mod 2"
t 0 512 ""          -- "2^3^2"
t 0 0.5 ""          -- "2^-1"
t 0 1.41421 ""      -- "2^0.5"
for dot in "·" "⋅" "∙" "•" "×"; do t 0 6 "" -- "2${dot}3"; done
t 0 9 ""            -- "6÷2{1+2}"

echo "== brackets"
t 0 11 ""           -- "15+4+3}/2"
t 0 7 ""            -- "2+4+8}/2"
t 0 4 ""            -- "-2}^2"
t 0 18 ""           -- "2+4}3"
t 0 6 ""            -- "2}{3}"
t 0 12 ""           -- "{1+2}+3}x2"
t 0 10 ""           -- "1+2}x3}+1"
t 0 9 ""            -- "1+2)x3}"
t 0 14 ""           -- "1+{2x3}}x2"
t 0 2.5 ""          -- "{1}+4}/2"
t 2 "" "expected '}' to match '{'" -- "{1+2)"
t 2 "" "unexpected '}'"            -- "}"
t 2 "" "missing '}'"               -- "{1+2"

echo "== implicit multiplication, π, √, superscripts"
t 0 6.28319 ""      -- "2π"
t 0 75.39822 ""     -- "2π12"
t 0 4.24264 ""      -- "3√2"
t 0 9 ""            -- "3²"
t 0 8 ""            -- "2³"
t 0 -4 ""           -- "-2²"
t 0 4 ""            -- "√4²"
t 0 9 ""            -- "{1+2}²"
t 0 12 ""           -- "2²3"
t 0 64 ""           -- "2²³"
t 0 64 ""           -- "2²^3"
t 0 9.8696 ""       -- "π²"
t 0 1.5 ""          -- "√{9/4}"
t 0 3 ""            -- "sqrt 9"
t 2 "" "unexpected '²'" -- "²"

echo "== lists and ranges"
t 0 "1 2 3" ""      -- 1 2 3
t 0 "1 2 3" ""      -- "1,2,3,"
t 0 "1 234" ""      -- "1, 234"
t 0 "1 -2" ""       -- 1 -2
t 0 "-2 3" ""       -- -2 3
t 0 -1 ""           -- "1 - 2"
t 0 "1 -5" ""       -- "1 -2 - 3"
t 0 "3 3" ""        -- "1 + 2 3"
t 0 "2 3.14159" ""  -- "2 π"
t 0 "2 4 6" ""      -- "{1 2 3}x2"
t 0 "2 4 8" ""      -- "2^{1 2 3}"
t 0 "11 22" ""      -- "{1 2}+{10 20}"
t 0 "1 2 3" ""      -- "√{1 4 9}"
t 0 "1 4 9" ""      -- "{1 2 3}²"
t 0 "4 3" ""        -- "2² 3"
t 0 "2 4 6" ""      -- "1 2 3}x2"
t 0 "1 2 3 4 5" ""  -- "1..5"
t 0 "1 2 3 4 5" ""  -- "1…5"
t 0 "1 2 3 4 5" ""  -- "1...5"
t 0 "3 2 1" ""      -- "3..1"
t 0 "1 2 3 4 5 6" "" -- "1..2x3"
t 1 "" "can't combine lists of 2 and 3" -- "{1 2}+{1 2 3}"
t 1 "" "range is longer than"           -- "1..2000000"
t 1 "" "a range needs a single number"  -- "{1 2}..5"
t 2 "" "is ambiguous"                   -- "1,234"
t 2 "" "is ambiguous"                   -- "1,234.5"
t 2 "" "looks like digit grouping"      -- "1 000 x 3"
t 2 "" "looks like digit grouping"      -- 1 000 x 3

echo "== functions"
t 0 6 ""            -- "sum 1 2 3"
t 0 6 ""            -- "SUM(1,2,3)"
t 0 5050 ""         -- "sum 1..100"
t 0 12 ""           -- "∑1,2,3}x2"
t 0 12 ""           -- "sum{1 2 3}x2"
t 0 6 ""            -- "2sum{1 2}"
t 0 6 ""            -- "Σ1 2 3"
t 0 24 ""           -- "∏1 2 3 4"
t 0 24 ""           -- "Π1..4"
t 0 2.5 ""          -- "avg 1 2 3 4"
t 0 5 ""            -- "avg 3,4,8"
t 0 2 ""            -- avg 1,2,3
t 0 2.5 ""          -- "mean{1 2 3 4}"
t 0 2.5 ""          -- "$MACRON 1 2 3 4"
t 0 2.5 ""          -- "$OVERLINE{1 2 3 4}"
t 0 4 ""            -- "2$MACRON{1 3}"
t 0 "2 6" ""        -- "2x{1 3}"
t 0 2 ""            -- "median 3 1 2"
t 0 2.5 ""          -- "median 4 1 3 2"
t 0 3 ""            -- "max 3 -7 2"
t 0 -7 ""           -- "min 3 -7 2"
t 0 2.13809 ""      -- "stdev 2 4 4 4 5 5 7 9"
t 0 10 ""           -- "2xmax{1 5}"
t 0 2 ""            -- "abs -3 + 1"
t 0 "3.0 -3.0 2.0" "" -- "round{2.5 -2.5 2.4}"
t 0 3 ""            -- "floor 7/2"
t 0 4 ""            -- "ceil 7/2"
t 0 -4 ""           -- "floor -7/2"
t 0 3 ""            -- "log 1000"
t 0 1 ""            -- "ln exp 1"
t 0 1 ""            -- "sin{pi/2}"
t 0 0 ""            -- "sum{}"
t 0 14.49138 ""     -- "√[∑1…2³ + ∏3…4]"
t 2 "" "needs numbers after it" -- "sum"
t 2 "" "unexpected character 'c'" -- "count 1 2"
t 1 "" "mean of an empty list"  -- "avg{}"
t 1 "" "stdev needs at least two numbers" -- "stdev 5"

echo "== maths errors: exit 1"
t 1 "" "division by zero"       -- "1/0"
t 1 "" "division by zero"       -- "4/0.0"
t 1 "" "division by zero"       -- "0^-1"
t 1 "" "modulo by zero"         -- "5 mod 0"
t 1 "" "square root of a negative number" -- "√-4"
t 1 "" "result out of range"    -- "ln 0"
t 1 "" "undefined result"       -- "ln -1"
t 1 "" "result out of range"    -- "9^9^9"

echo "== output precision"
t 0 1.4142135624 ""             -- "-p=10" "√2"
t 0 1.4142135624 ""             -- -p 10 "√2"
t 0 1.41 ""                     -- -p2 "√2"
t 0 1 ""                        -- -p0 "√2"
t 0 6.283185307179586 ""        -- "--precision=max" "2π"
t 0 0.3333333333333333 ""       -- --precision max "1/3"
t 0 0.02 ""                     -- -p2 "1/64"
t 0 -0.33 ""                    -- -p2 -- -1/3
t 0 3.14159e17 ""               -- "πx10^17"
t 0 3.141592653589793e17 ""     -- -p max "πx10^17"
t 0 -0.000001 ""                -- "-0.000001x1"
t 0 0.0 ""                      -- -p2 "-0.000001x1"   # "-0.00" tidies to 0, and a decimal input keeps ".0"
t 0 0.0 ""                      -- -p2 "-0.000001xπ"

echo "== syntax and usage errors: exit 2"
t 2 "" "unexpected end of expression" -- "1+"
t 2 "" "unexpected '*'"               -- "1+*2"
t 2 "" "unexpected end of expression" -- "-"
t 2 "" "invalid number '1.2.3'"       -- "1.2.3"
t 2 "" "unexpected character '.'"    -- "."
t 2 "" "unexpected character '&'"    -- "2&3"
t 2 "" "unexpected character 'r'"    -- -- -r
t 2 "" "looks like the shell expanded avg" -- avg1 avg2 avg3
t 2 "" "unknown option '--bogus'"     -- --bogus 1
t 2 "" "invalid precision 'x'"        -- -p x 1
t 2 "" "-p requires a value"          -- -p
t 2 "" "needs stdin"                  -- -r sum
t 2 "" "isn't supported"              -- --pretty 1
t 2 "" "reads stdin"                  -- "max@-min@"
t 2 "" "Usage:"                       --

# --help and --version print to stdout and succeed
for opt in --help -h; do
  out=$(java Calc.java $opt 2>/dev/null); code=$?
  if [[ $code == 0 && $out == Usage:* ]]; then pass=$((pass + 1)); echo "PASS  $opt"; else fail=$((fail + 1)); echo "FAIL  $opt: exit=$code"; fi
done
out=$(java Calc.java --version 2>/dev/null); code=$?
if [[ $code == 0 && $out == "calc (Java) "* ]]; then pass=$((pass + 1)); echo "PASS  --version"; else fail=$((fail + 1)); echo "FAIL  --version: $out"; fi

# The caret points at the offending character
caret=$(java Calc.java "12+*3" 2>&1 | grep -v '^Picked up' | tail -1)
if [[ $caret == "     ^" ]]; then pass=$((pass + 1)); echo "PASS  caret position"; else fail=$((fail + 1)); echo "FAIL  caret position: '$caret'"; fi

# In a C locale the JVM can't decode non-ASCII arguments (macOS always uses UTF-8)
if [[ $(uname) != Darwin ]]; then
  out=$(LC_ALL=C LANG=C java Calc.java "√2" 2>&1 | grep -v '^Picked up'); code=$?
  if [[ $out == *"couldn't be decoded"* ]]; then pass=$((pass + 1)); echo "PASS  C locale: non-ASCII argument is reported"; else fail=$((fail + 1)); echo "FAIL  C locale: $out"; fi
fi

echo "----"
echo "$pass passed, $fail failed"
[[ $fail == 0 ]]
