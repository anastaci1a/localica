#!/bin/sh
# Git merge driver for line-set files such as .github/prototype-risk-classes.txt.
# A plain union merge keeps a line twice when both sides add it, which the risk-inventory contract
# rejects. This takes the union and then keeps only the first occurrence of each non-blank, non-comment line.
# Register once per clone (worktrees share it):
#   git config merge.union-dedupe.driver 'sh tools/git/merge-union-dedupe.sh %O %A %B'
# Without that config Git falls back to a normal text merge for this file.
set -eu
git merge-file -q --union "$2" "$1" "$3" || true
awk '/^[[:space:]]*(#|$)/ { print; next } !seen[$0]++' "$2" > "$2.dedupe" && mv "$2.dedupe" "$2"
