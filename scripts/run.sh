#!/usr/bin/env bash

set -o errexit
set -o errtrace
set -o nounset
set -o pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

DEMOS=(
    ConcurrentModificationExceptionDemo
    ReentrantModificationExceptionDemo
    WaitNotifyDemo
    WheresWally
    MixedHotspot
)

usage() {
    echo "Usage: $0 <demo> [--record]"
    echo ""
    echo "Demos:"
    for d in "${DEMOS[@]}"; do
        echo "  $d"
    done
    exit 1
}

[[ $# -lt 1 ]] && usage

DEMO="$1"
RECORD=false
[[ "${2:-}" == "--record" ]] && RECORD=true

# Validate demo name
VALID=false
for d in "${DEMOS[@]}"; do
    [[ "$d" == "$DEMO" ]] && VALID=true
done
$VALID || { echo "Unknown demo: $DEMO"; usage; }

MAIN_CLASS="io.undo.demos.${DEMO}"

AGENT_ARGS=()
if $RECORD; then
    if [[ -z "${LR4J_HOME:-}" ]]; then
        echo "You must set LR4J_HOME to use --record"
        exit 1
    elif [[ ! -f "${LR4J_HOME}/lr4j-record-1.0/lr4j_agent_x64.so" ]]; then
        echo "'${LR4J_HOME}/lr4j-record-1.0/lr4j_agent_x64.so' not found!"
        exit 1
    fi
    RECORDING="${DEMO}.undo"
    AGENT_ARGS=(-XX:-Inline -XX:TieredStopAtLevel=1 -agentpath:${LR4J_HOME}/lr4j-record-1.0/lr4j_agent_x64.so=save_on=always,output=${RECORDING})
fi

echo "Building..."
mvn -f "${PROJECT_DIR}/pom.xml" -q compile

$RECORD && echo "Running ${DEMO} (recording to ${RECORDING})" || echo "Running ${DEMO}"
java ${AGENT_ARGS[@]+"${AGENT_ARGS[@]}"} -cp "${PROJECT_DIR}/target/classes" "${MAIN_CLASS}"
