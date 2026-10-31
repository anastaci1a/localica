#!/bin/sh
# Normalize only explicitly supplied paths; never follow symlinks.
set -eu
[ "$#" -gt 0 ] || { echo 'Usage: fix-permissions.sh PATH...' >&2; exit 2; }
for path do
  find "$path" -type d -exec chgrp users {} + -exec chmod g+rwx {} +
  find "$path" -type f -exec chgrp users {} + -exec chmod g+rw {} +
done
