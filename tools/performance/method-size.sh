#!/bin/bash
# method-size.sh <apk> [simple-class-name] [method] [--lines [N]]
# Inline-class parameters mangle the dex name (Foo-abc123); the bare name still matches.
# Reports a method's dex size against ART's compiler and Nterp limits (#682), and optionally the
# code units each source line contributes (from the dex positions table), largest first.
#
#   code units  : ART skips JIT/AOT compilation at >= 16383 (Compiler::IsPathologicalCase)
#   frame       : Nterp needs 160 + registers*8 + 24 + outs*4 (rounded to 16) <= 3072 bytes on arm64
#
# Needs dexdump from an SDK build-tools dir: ANDROID_HOME, or DEXDUMP pointing at the binary.
set -eu
apk=${1:?usage: method-size.sh <apk> [simple-class-name] [method] [--lines [N]]}
cls=${2:-BrowserScreenKt}; method=${3:-BrowserScreen}
lines=0; top=40
if [ "${4:-}" = "--lines" ]; then lines=1; top=${5:-40}; fi
dexdump=${DEXDUMP:-}
if [ -z "$dexdump" ]; then
  for d in "${ANDROID_HOME:-/sdk}"/build-tools/*/dexdump; do dexdump=$d; done
fi
[ -x "${dexdump:-}" ] || { echo "dexdump not found; set ANDROID_HOME or DEXDUMP" >&2; exit 2; }
tmp=$(mktemp -d); trap 'rm -rf "${tmp:?}"' EXIT
unzip -q -o "$apk" 'classes*.dex' -d "$tmp"
found=0
for dex in "$tmp"/classes*.dex; do
  out=$("$dexdump" "$dex" 2>/dev/null | awk -v cls="$cls" -v method="$method" -v lines="$lines" -v dex="$(basename "$dex")" '
    /Class descriptor/ { inclass = index($0, "/" cls ";") > 0 }
    inclass && /^ +name +: / { name = $3; gsub(/\x27/, "", name); want = (name == method || index(name, method "-") == 1); inpos = 0 }
    want && /^ +registers +: / { regs = $3 }
    want && /^ +ins +: / { ins = $3 }
    want && /^ +outs +: / { outs = $3 }
    want && /^ +insns size +: / { units = $4 }
    want && /^ +positions +:/ { inpos = 1; n = 0; next }
    want && inpos && /^ +0x[0-9a-f]+ line=/ { addr[n] = strtonum($1); sub(/line=/, "", $2); line[n] = $2; n++; next }
    want && inpos && !/^ +0x/ {
      frame = 160 + regs * 8 + 24 + outs * 4; frame = int((frame + 15) / 16) * 16
      printf "%s %s.%s: code units %d (limit 16383, target 12000) registers %d ins %d outs %d nterp frame %d B (limit 3072) regs*8+outs*4 %d (target 2800)\n", dex, cls, method, units, regs, ins, outs, frame, regs * 8 + outs * 4
      if (lines) {
        for (i = 0; i < n; i++) { end = (i + 1 < n) ? addr[i + 1] : units; per[line[i]] += end - addr[i] }
        for (l in per) printf "%d %d\n", per[l], l
      }
      exit
    }
  ') || true
  [ -n "$out" ] || continue
  found=1
  echo "$out" | head -n 1
  if [ "$lines" = 1 ]; then echo "$out" | tail -n +2 | sort -rn | head -n "$top" | awk '{printf "  line %5d: %6d code units\n", $2, $1}'; fi
  break
done
[ "$found" = 1 ] || { echo "method $cls.$method not found in $apk" >&2; exit 1; }
