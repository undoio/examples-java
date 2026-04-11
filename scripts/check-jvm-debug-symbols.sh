#!/usr/bin/env bash
#
# Check whether the current JVM includes native debug symbols,
# which are required for Java recordings to replay correctly.

set -euo pipefail

# --- Preflight: check required tools ---

if ! command -v java &>/dev/null; then
  echo "ERROR: java not found on PATH." >&2
  exit 1
fi

if ! command -v readelf &>/dev/null; then
  echo "ERROR: readelf not found. Install binutils:" >&2
  echo "       sudo apt-get install binutils   # Debian/Ubuntu" >&2
  echo "       sudo yum install binutils        # RHEL/Fedora" >&2
  exit 1
fi

# --- Locate java.home ---

java_home=$(java -XshowSettings:properties -version 2>&1 \
  | grep -m1 'java.home' | awk '{print $3}')

if [[ -z "$java_home" ]]; then
  echo "ERROR: Could not determine java.home." >&2
  exit 1
fi

# --- Find libjvm.so (path varies by JDK version and architecture) ---

libjvm=""
for candidate in \
  "$java_home/lib/server/libjvm.so" \
  "$java_home/lib/*/server/libjvm.so" \
  "$java_home/jre/lib/*/server/libjvm.so" \
  "$java_home/jre/lib/server/libjvm.so"; do
  # Use ls to expand globs; take the first match
  for f in $candidate; do
    if [[ -f "$f" ]]; then
      libjvm="$f"
      break 2
    fi
  done
done

if [[ -z "$libjvm" ]]; then
  echo "ERROR: Could not find libjvm.so under $java_home" >&2
  exit 1
fi

# --- Check for debug symbols ---
# Symbols may be embedded in libjvm.so itself (JDK 9+) or in a separate
# .debug file located via the ELF build-id (common on JDK 8 with -dbg packages).

has_symbols=false

# Check libjvm.so directly
if grep -q '_ZTV8Metadata' <(readelf -s "$libjvm" 2>/dev/null); then
  has_symbols=true
fi

# Check separate debug file via build-id
if [[ "$has_symbols" == false ]]; then
  build_id=$(readelf -n "$libjvm" 2>/dev/null \
    | sed -n 's/.*Build ID: \([0-9a-f]\+\)/\1/p')
  if [[ -n "$build_id" ]]; then
    debug_file="/usr/lib/debug/.build-id/${build_id:0:2}/${build_id:2}.debug"
    if [[ -f "$debug_file" ]] \
       && grep -q '_ZTV8Metadata' <(readelf -s "$debug_file" 2>/dev/null); then
      has_symbols=true
    fi
  fi
fi

if [[ "$has_symbols" == true ]]; then
  echo "OK: Debug symbols found for $libjvm"
else
  echo "MISSING: Debug symbols not found in $libjvm"
  echo ""
  # Detect JDK major version portably (no PCRE needed)
  jdk_ver=$(java -version 2>&1 | head -1 | sed 's/.*"\([0-9]\+\).*/\1/')
  # JDK 8 reports as "1.8.x", so java -version starts with "1."
  if [[ "$jdk_ver" == "1" ]]; then
    jdk_ver=$(java -version 2>&1 | head -1 | sed 's/.*"1\.\([0-9]\+\).*/\1/')
  fi
  echo "To fix this, either:"
  echo "  1. Install the debug symbols package for your JDK (version $jdk_ver):"
  echo "       sudo apt-get install openjdk-${jdk_ver}-dbg        # Debian/Ubuntu"
  echo "       sudo yum debuginfo-install java-${jdk_ver}-openjdk  # RHEL/Fedora"
  echo "  2. Use a JDK build that includes debug symbols by default."
  exit 1
fi
