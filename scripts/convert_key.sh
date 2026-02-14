#!/bin/bash
# Convert a binary key file to a PEM-style text key file with base64 encoding.
#
# Usage: convert_key.sh <binary_key_file> [output_file]
#
# If output_file is omitted, prints to stdout.

set -euo pipefail

if [ $# -lt 1 ] || [ $# -gt 2 ]; then
    echo "Usage: $0 <binary_key_file> [output_file]" >&2
    exit 1
fi

INPUT="$1"

if [ ! -f "$INPUT" ]; then
    echo "Error: file not found: $INPUT" >&2
    exit 1
fi

output() {
    echo "-----BEGIN UNDO KEY-----"
    base64 < "$INPUT"
    echo "-----END UNDO KEY-----"
}

if [ $# -eq 2 ]; then
    output > "$2"
    echo "Wrote key to $2"
else
    output
fi
