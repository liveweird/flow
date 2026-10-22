#!/bin/sh
# Tripwire for a silently unused AppCDS archive (build-times.md WHY 27, ci.md): a mismatching archive (another JVM, a changed jar,
# `-XX:-UseCompressedOops`/`ObjectAlignmentInBytes` added to the baked flags) never fails a start - the app boots with no classes
# shared and is SLOWER than without the archive - so CI could not tell. This runs the image's own launcher (so the classpath and the
# baked JVM flags are the runtime's exact ones, plus the image's SERVER_OPTS archive flag) with `-XX:+PrintSharedArchiveAndExit`:
# the JVM validates the archive against that classpath and those flags, prints what it checked and exits non-zero on any mismatch,
# without starting the app (no database needed, well under a second).
#
#   sh scripts/check-cds-archive.sh <image>        exit 0 = the archive is valid for the runtime, 1 = it is not (or is not wired)
#   CDS_CHECK_EXTRA_OPTS="-XX:-UseCompressedOops" sh scripts/check-cds-archive.sh <image>   # prove the failure path locally
set -eu
image=${1:?usage: check-cds-archive.sh <image>}
if out=$(docker run --rm -e JAVA_OPTS="-XX:+PrintSharedArchiveAndExit ${CDS_CHECK_EXTRA_OPTS:-}" "$image" 2>&1); then
  # Our archive lists the application jars; the JDK's default archive (SERVER_OPTS cleared) would pass without them.
  if echo "$out" | grep -q 'checking shared classpath entry: /app/lib/server-'; then
    echo "AppCDS archive valid: $(echo "$out" | grep -c 'checking shared classpath entry') classpath entries checked"
    exit 0
  fi
  echo "::error::the JVM does not use /app/cds/flow.jsa at all (is SERVER_OPTS cleared in the Dockerfile?)" >&2
  echo "$out" | tail -5 >&2
  exit 1
fi
echo "::error::the AppCDS archive /app/cds/flow.jsa does not fit the runtime (changed jar, baked JVM flags, other JDK): the image would start WITHOUT it, slower than master" >&2
echo "$out" | grep -E '\[(error|warning)\]|rror occurred' | head -8 | cut -c1-200 >&2
exit 1
