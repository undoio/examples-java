#!/usr/bin/env bash
#
# Check whether the current JVM includes native debug symbols by
# attempting to attach the HotSpot Serviceability Agent (SA).
#
# SA requires debug symbols to read JVM internals — if it can
# successfully inspect a JVM process, symbols are present.

set -euo pipefail

if ! command -v java &>/dev/null; then
  echo "ERROR: java not found on PATH." >&2
  exit 1
fi

# --- Resolve all tool paths from the same JDK ---

java_home=$(java -XshowSettings:properties -version 2>&1 \
  | grep -m1 'java.home' | awk '{print $3}')

# On JDK 8, java.home points to the jre/ subdirectory
jdk_root="$java_home"
if [[ -f "$java_home/../bin/javac" ]]; then
  jdk_root=$(cd "$java_home/.." && pwd)
fi

java_bin="$jdk_root/bin/java"
javac_bin="$jdk_root/bin/javac"

if [[ ! -x "$javac_bin" ]]; then
  echo "ERROR: javac not found at $javac_bin (JDK required, not just JRE)." >&2
  exit 1
fi

# Detect JDK major version
jdk_ver=$("$java_bin" -version 2>&1 | head -1 | sed 's/.*"\([0-9]\+\).*/\1/')
if [[ "$jdk_ver" == "1" ]]; then
  jdk_ver=$("$java_bin" -version 2>&1 | head -1 | sed 's/.*"1\.\([0-9]\+\).*/\1/')
fi

# --- Locate SA tool from the same JDK ---

sa_mode=""
jhsdb_bin="$jdk_root/bin/jhsdb"
sa_jdi_jar="$jdk_root/lib/sa-jdi.jar"

if [[ -x "$jhsdb_bin" ]]; then
  sa_mode="jhsdb"
elif [[ -f "$sa_jdi_jar" ]]; then
  sa_mode="sa-jdi"
else
  echo "ERROR: Could not find SA tools (jhsdb or sa-jdi.jar) in $jdk_root." >&2
  echo "       Make sure you have a full JDK installed." >&2
  exit 1
fi

# --- Build a minimal probe class ---

probe_dir=$(mktemp -d)
trap 'rm -rf "$probe_dir"' EXIT

cat > "$probe_dir/Probe.java" <<'JAVA'
public class Probe {
  public static void main(String[] a) throws Exception {
    Thread.sleep(60000);
  }
}
JAVA
"$javac_bin" -d "$probe_dir" "$probe_dir/Probe.java" 2>/dev/null

# --- Launch probe process ---

"$java_bin" -cp "$probe_dir" -Xmx8m -XX:+UseSerialGC Probe &
probe_pid=$!
trap 'kill "$probe_pid" 2>/dev/null; wait "$probe_pid" 2>/dev/null; rm -rf "$probe_dir"' EXIT
sleep 2

if ! kill -0 "$probe_pid" 2>/dev/null; then
  echo "ERROR: Probe process failed to start." >&2
  exit 1
fi

# --- Attach SA ---

sa_output=""
sa_exit=0
case "$sa_mode" in
  jhsdb)
    sa_output=$("$jhsdb_bin" jinfo --pid "$probe_pid" 2>&1) || sa_exit=$?
    ;;
  sa-jdi)
    sa_output=$("$java_bin" -cp "$sa_jdi_jar" \
      sun.jvm.hotspot.tools.JInfo "$probe_pid" 2>&1) || sa_exit=$?
    ;;
esac

# --- Interpret result ---

if [[ $sa_exit -eq 0 ]]; then
  echo "OK: Debug symbols found (SA attached to JDK $jdk_ver successfully)"
else
  echo "MISSING: Debug symbols not found for JDK $jdk_ver"
  echo ""
  sa_error=$(echo "$sa_output" | grep -m1 -E 'does not appear to be polymorphic|could not find symbol|Error' || true)
  if [[ -n "$sa_error" ]]; then
    echo "  SA reported: $sa_error"
    echo ""
  fi
  echo "To fix this, either:"
  echo "  1. Install the debug symbols package for your JDK (version $jdk_ver):"
  echo "       sudo apt-get install openjdk-${jdk_ver}-dbg        # Debian/Ubuntu"
  echo "       sudo yum debuginfo-install java-${jdk_ver}-openjdk  # RHEL/Fedora"
  echo "  2. Use a JDK build that includes debug symbols by default."
  exit 1
fi
