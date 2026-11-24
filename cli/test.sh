#!/usr/bin/env bash
# End-to-end tests for the command-line calculator. Each case runs it exactly as a user would,
# and checks stdout, stderr and the exit code. Most cases are ported from calc-cli
# (https://github.com/lcrilly/calc-cli), the Rust tool this mirrors.
#
#   ./test.sh            from source: java --enable-native-access=ALL-UNNAMED Calc.java
#   ./test.sh --built    runs ./build.sh, then tests ./jcalc
set -u
cd "$(dirname "$0")"

CALC=(java --enable-native-access=ALL-UNNAMED Calc.java)
if [[ ${1:-} == --built ]]; then
  ./build.sh >/dev/null || exit 1
  CALC=(./jcalc)
fi
echo "Testing: ${CALC[*]}"

# Isolate the tests from the user's settings: a plain number style, but UTF-8 arguments
# (π, √, ²). macOS has no C.UTF-8, but its JVM always decodes arguments as UTF-8.
unset CALC_PRETTY CALC_NUMBER_FORMAT LC_NUMERIC LANG
if [[ $(uname) == Darwin ]]; then export LC_ALL=C; else export LC_ALL=C.UTF-8; fi

pass=0 fail=0
errfile=$(mktemp)
trap 'rm -f "$errfile"' EXIT

ok()  { pass=$((pass + 1)); printf 'PASS  %s\n' "$1"; }
bad() { fail=$((fail + 1)); printf 'FAIL  %s\n' "$1"; }

# t <exit> <stdout> <stderr substring> -- <args...>        stdin is empty (/dev/null)
# ti <stdin> <exit> <stdout> <stderr substring> -- <args...> stdin is given (\n for newlines)
# In the expected stdout, spaces stand for newlines: a list prints one number per line.
t() { run "" /dev/null "$@"; }
ti() { local input=$1; shift; run "$input" <(printf '%b' "$input") "$@"; }
run() {
  local label=$1 from=$2 want_exit=$3 want_out=${4// /$'\n'} want_err=$5; shift 6
  local out err code
  out=$("${CALC[@]}" "$@" <"$from" 2>"$errfile"); code=$?
  err=$(grep -v '^Picked up' "$errfile")   # the JVM's JAVA_TOOL_OPTIONS notice, if set
  local name="$*${label:+  <<< ${label//$'\n'/\\n}}"
  if [[ $code == "$want_exit" && $out == "$want_out" && $err == *"$want_err"* ]]; then
    pass=$((pass + 1)); printf 'PASS  %-40s -> %s\n' "$name" "$(echo ${out:-$err} | head -c 60)"
  else
    fail=$((fail + 1)); printf 'FAIL  %-40s exit=%s out=%q err=%q\n      want exit=%s out=%q err~%q\n' \
      "$name" "$code" "$out" "$err" "$want_exit" "$want_out" "$want_err"
  fi
}

# te <stdin> <exact stderr> -- <args...>: the whole error message, caret and all
te() {
  local input=$1 want=$2; shift 3
  local err
  err=$(printf '%b' "$input" | "${CALC[@]}" "$@" 2>&1 >/dev/null | grep -v '^Picked up')
  if [[ $err == "$want" ]]; then ok "error layout: $*"; else bad "error layout: $* got $(printf '%q' "$err") want $(printf '%q' "$want")"; fi
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
t 0 5 ""            -- "2.5x2"
t 0 "0.5 3" ""      -- "0.5 3"
t 0 1.5 ""          -- "1.50"
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
t 1 "" "expected '}' to match the bracket at position 1" -- "{1+2)"
t 1 "" "unexpected '}'"            -- "}"
t 1 "" "missing '}'"               -- "{1+2"

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

echo "== factorial"
t 0 120 ""          -- "5!"
t 0 1 ""            -- "0!"
t 0 -6 ""           -- "-3!"
t 0 64 ""           -- "2^3!"
t 0 36 ""           -- "3!²"
t 0 24 ""           -- "2²!"
t 0 4.89898 ""      -- "√4!"
t 0 12 ""           -- "2x3!"
t 0 24 ""           -- "3!4"
t 0 720 ""          -- "{3!}!"
t 0 "1 2 6 24" ""   -- "{1 2 3 4}!"
t 0 "120 3" ""      -- "5! 3"
t 0 "1 2 6 24 120" "" -- "fact 1..5"
t 0 120 ""          -- "factorial(5)"
t 0 240 ""          -- "FACT{5}x2"
t 0 240 ""          -- "5!" x2
t 0 8683317618811886495518194401280000000 "" -- "33!"
t 0 295232799039604140847618609643520000000 "" -- "34!"    # exact, where calc-cli's i128 gives 2.95233e38
t 1 "" "whole number"                 -- "2.5!"
t 1 "" "whole number"                 -- "{-1}!"
t 1 "" "whole number"                 -- "π!"
t 1 "" "'!!' isn't supported"         -- "3!!"
t 1 "" "result out of range"          -- "100000!"

echo "== mode, inverse and hyperbolic trig, degrees"
t 0 2 ""            -- "mode 1 2 2 3"
t 0 "1 3" ""        -- "mode 3 1 3 1 2"
t 0 "1 2 3" ""      -- "mode 1 2 3"
t 0 0.5 ""          -- "mode 0.5 1/2 2"
t 0 8 ""            -- "2xmode{1 4 4}"
t 1 "" "mode of an empty list" -- "mode{}"
t 0 1.5708 ""       -- "asin 1"
t 0 "0 1.5708" ""   -- "acos{1 0}"
t 0 3.14159 ""      -- "4atan 1"
t 0 1.1752 ""       -- "sinh 1"
t 0 1 ""            -- "cosh 0"
t 0 0.76159 ""      -- "tanh 1"
t 0 0.5 ""          -- "sin asin 0.5"
t 1 "" "undefined result" -- "acos 2"
t 0 0.5 ""          -- "sin 30°"
t 0 0.5 ""          -- "cos 60°"
t 0 1 ""            -- "tan 45°"
t 0 0 ""            -- "cos 90°"
t 0 "0 1 0" ""      -- "sin {0 90 180}°"
t 0 0.5 ""          -- "sin 30º"
t 0 3.14159 ""      -- "180°"
t 0 30 ""           -- "asin{0.5}/1°"

echo "== lists and ranges"
t 0 "1 2 3" ""      -- 1 2 3
t 0 "1 2 3" ""      -- "1,2,3,"
t 0 "1 234" ""      -- "1, 234"
t 0 "3 5" ""        -- "3,5"
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
t 0 10 ""           -- "sum 1 … 4"
t 0 "1 2 3 4 5 6" "" -- "1..2x3"
t 1 "" "can't combine lists of 2 and 3" -- "{1 2}+{1 2 3}"
t 1 "" "range is longer than"           -- "1..2000000"
t 1 "" "a range needs a single number"  -- "{1 2}..5"
t 1 "" "is ambiguous"                   -- "1,234"
t 1 "" "is ambiguous"                   -- "1,234.5"
t 1 "" "looks like digit grouping"      -- "1 000 x 3"
t 1 "" "looks like digit grouping"      -- 1 000 x 3

echo "== functions"
t 0 6 ""            -- "sum 1 2 3"
t 0 6 ""            -- "SUM(1,2,3)"
t 0 5050 ""         -- "sum 1..100"
t 0 12 ""           -- "∑1,2,3}x2"
t 0 12 ""           -- "sum{1 2 3}x2"
t 0 6 ""            -- "2sum{1 2}"
t 0 5 ""            -- "sum 1 2 x2"
t 0 6 ""            -- "Σ1 2 3"
t 0 24 ""           -- "∏1 2 3 4"
t 0 24 ""           -- "Π1..4"
t 0 2.5 ""          -- "avg 1 2 3 4"
t 0 5 ""            -- "avg 3,4,8"
t 0 2 ""            -- avg 1,2,3
t 0 2.5 ""          -- "$MACRON 1 2 3 4"
t 0 2.5 ""          -- "$OVERLINE{1 2 3 4}"
t 0 4 ""            -- "2$MACRON{1 3}"
t 0 "2 6" ""        -- "2x{1 3}"
t 0 2.5 ""          -- "median 4 1 3 2"
t 0 -7 ""           -- "min 3 -7 2"
t 0 3 ""            -- "max 3 -7 2"
t 0 2.13809 ""      -- "stdev 2 4 4 4 5 5 7 9"
t 0 10 ""           -- "2xmax{1 5}"
t 0 2 ""            -- "abs -3 + 1"
t 0 "3 -3 2" ""     -- "round{2.5 -2.5 2.4}"
t 0 3 ""            -- "floor 7/2"
t 0 4 ""            -- "ceil 7/2"
t 0 -4 ""           -- "floor -7/2"
t 0 3 ""            -- "log 1000"
t 0 1 ""            -- "ln exp 1"
t 0 0 ""            -- "sum{}"
t 0 14.49138 ""     -- "√[∑1…2³ + ∏3…4]"
t 1 "" "unexpected character 'c'" -- "count 1 2"
t 1 "" "mean of an empty list"    -- "avg{}"
t 1 "" "stdev needs at least two numbers" -- "stdev 5"

echo "== maths errors"
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
t 0 0 ""                        -- -p2 "-0.000001x1"

echo "== stdin"
ti '1\n2\n3\n4\n' 0 10 ""       -- sum
ti '1 2\n3 4\n' 0 10 ""         -- sum
ti '3\n9\n4\n' 0 6 ""           -- "max{@}-min{@}"
ti '3\n9\n4\n' 0 6 ""           -- "max@-min@"
ti '' 0 0 ""                    -- sum
t  0 0 ""                       -- sum
ti '1+1\n\n2π\n' 0 "2 6.28319" ""  --
ti '2π\n' 0 6.283185307179586 "" -- --precision=max
ti '1\n2\n3\n' 0 "2 4 8" ""     -- "2^"
ti '1\n2\n3\n' 0 "2.5 3 3.5" "" -- "+4}/2"
ti '-2\n' 0 4 ""                -- "^2"
ti '-2\n3\n' 0 "4 9" ""         -- "²"
ti '1\n2\n3\n' 0 "2 4 6" ""     -- x2
ti '3\n4\n5\n' 0 "6 24 120" ""  -- "!"
ti '30\n90\n' 0 "0.5 1" ""      -- sin "@°"
ti '180\n' 0 3.14159 ""         -- "°"
ti '5\n' 0 3 ""                 -- "1+2"
ti '5\n' 0 -2 ""                -- "-1x2"
ti '5\n' 0 -1 ""                -- "{-1}"
ti '2\n' 0 3 ""                 -- "1+"
t  0 "" ""                      -- "1+"
t  0 "" ""                      --

echo "== a lone negative number continues stdin"
ti '10\n' 0 9 ""                -- -1
ti '10\n20\n' 0 "9 19" ""       -- - 1
ti '2\n' 0 1.5 ""               -- -p1 -0.5
ti '' 0 -3 ""                   -- -3
t  0 -3 ""                      -- -3
ti '10\n' 0 "-1 -2" ""          -- -1 -2

echo "== rows"
ti '1 2\n3 4\n' 0 "3 7" ""      -- -r sum
out=$(printf '1 2\n3 4\n' | "${CALC[@]}" --rows x10 2>&1)   # rows keep their spaces
[[ $out == $'10 20\n30 40' ]] && ok "--rows x10 -> rows of 2" || bad "--rows x10: $(printf '%q' "$out")"

echo "== bad lines"
ti '1\nfoo\n3\n' 1 "2 6" "line 2:"  -- x2
ti '1\nfoo\n3\n' 1 "" "line 2:"     -- sum
ti '1\n@\n' 1 "2" "'@' can only be used in arguments" -- x2
ti '1\n0\n' 1 "12" "line 2: division by zero" -- "12/"

echo "== errors show where"
te '' $'calc: missing \'}\'\n  2x{3+4\n    ^'                     -- "2x{3+4"
te '1\n2\n3\n' $'calc: missing \'}\'\n  x{2\n   ^'                -- "x{2"
te '1\n0\n' $'calc: line 2: division by zero\n  12/\n  ^^^'        -- "12/"
te '' $'calc: division by zero\n  1+2/0\n    ^^^'                 -- "1+2/0"
te '' $'calc: unexpected character \'q\'\n  1 q\n    ^'           -- "1 q"
te '' $'calc: can\'t combine lists of 2 and 3 numbers\n  {1 2}+{1 2 3}\n  ^^^^^^^^^^^^^' -- "{1 2}+{1 2 3}"
te '' $'calc: \'!!\' isn\'t supported: write {n!}! for the factorial of a factorial\n  5!!\n   ^^' -- "5!!"

echo "== pretty numbers"
t 0 3,141.6 ""                  -- --pretty=1,234.5 -p1 "1000π"
t 0 3.141,6 ""                  -- --pretty=1.234,5 -p1 "1000π"
ti '3,141.6\n' 0 3,142.6 ""     -- --pretty=1,234.5 +1
ti '3.141,6\n' 0 3.142,6 ""     -- --pretty=1.234,5 +1
t 0 3,80,997.5 ""               -- "--pretty=0,00,000.0" "∑1…1234}/2"
t 0 1234567 ""                  -- --pretty 1234567
t 2 "" "invalid number format 'nonsense'" -- --pretty=nonsense 1
export CALC_NUMBER_FORMAT=1.234,5
t 0 617.283,5 ""                -- --pretty "1234567/2"
t 1 "" "'.' as the decimal mark" -- "3,5"
export CALC_NUMBER_FORMAT=1,234.5 CALC_PRETTY=always
t 0 1,234,567 ""                -- 1234567
t 1 "" "ambiguous"              -- "1,234"
export CALC_PRETTY=auto
t 0 1234567 ""                  -- 1234567
unset CALC_NUMBER_FORMAT CALC_PRETTY

echo "== usage errors: exit 2"
t 2 "" "looks like the shell expanded avg" -- avg1 avg2 avg3
t 2 "" "unknown option '--bogus'"     -- --bogus 1
t 2 "" "unknown option '--3'"         -- "--3"
t 2 "" "invalid precision 'x'"        -- -p x 1
t 2 "" "-p requires a value"          -- -p
t 1 "" "unexpected character 'r'"     -- -- -r

for opt in --help -h; do
  out=$("${CALC[@]}" $opt 2>/dev/null </dev/null); code=$?
  if [[ $code == 0 && $out == "calc - "* ]]; then ok "$opt"; else bad "$opt: exit=$code"; fi
done
out=$("${CALC[@]}" --version 2>/dev/null </dev/null); code=$?
if [[ $code == 0 && $out == "calc (Java) "* ]]; then ok "--version"; else bad "--version: $out"; fi

echo "== streaming and pipes"
# Element-wise results appear as each line arrives (tail -f), not when stdin closes.
start=$(date +%s)
first=$({ echo 1; sleep 3; echo 2; } | "${CALC[@]}" x2 2>/dev/null | { read -r line; echo "$line $(( $(date +%s) - start ))"; cat >/dev/null; })
if [[ ${first% *} == 2 && ${first#* } -lt 3 ]]; then ok "streams: first result after ${first#* }s, before stdin closed"; else bad "streaming: '$first'"; fi
# A reader that goes away (| head) ends calc quietly, with success.
result=$(seq 1 200000 | "${CALC[@]}" x2 2>&1 | head -2 | tr '\n' ' '; echo "${PIPESTATUS[1]}")
out=${result% *} status=${result##* }
if [[ $out == "2 4" && $status == 0 ]]; then ok "| head: stops quietly (exit $status)"; else bad "| head: out='$out' exit=$status"; fi

echo "== locales"
# The caret points at the offending character
caret=$("${CALC[@]}" "12+*3" 2>&1 </dev/null | grep -v '^Picked up' | tail -1)
if [[ $caret == "     ^" ]]; then ok "caret position"; else bad "caret position: '$caret'"; fi
if [[ $(uname) != Darwin ]]; then
  # In a C locale the JVM can't decode non-ASCII arguments (macOS always uses UTF-8)
  out=$(LC_ALL=C "${CALC[@]}" "√2" 2>&1 </dev/null | grep -v '^Picked up')
  if [[ $out == *"couldn't be decoded"* ]]; then ok "C locale: non-ASCII argument is reported"; else bad "C locale: $out"; fi
  # Number styles from the C library's locale data, when those locales are installed
  if locale -a 2>/dev/null | grep -qi '^de_DE.utf'; then
    out=$(LC_ALL=de_DE.UTF-8 "${CALC[@]}" --pretty -p1 "1234567π" </dev/null)
    [[ $out == 3.878.506,6 ]] && ok "de_DE: $out" || bad "de_DE: $out"
  fi
  if locale -a 2>/dev/null | grep -qi '^en_IN.utf'; then
    out=$(LC_ALL=en_IN.UTF-8 "${CALC[@]}" --pretty -p1 "1234567π" </dev/null)
    [[ $out == 38,78,506.6 ]] && ok "en_IN (needs native access): $out" || bad "en_IN: $out"
    # Without native access, the pure-Java fallback can't do Indian grouping, and says nothing
    out=$(LC_ALL=en_IN.UTF-8 java Calc.java --pretty -p1 "1234567π" </dev/null 2>&1 | grep -v '^Picked up')
    [[ $out == 3,878,506.6 ]] && ok "en_IN, Java fallback: $out" || bad "en_IN fallback: $out"
  fi
fi

echo "----"
echo "$pass passed, $fail failed"
[[ $fail == 0 ]]
