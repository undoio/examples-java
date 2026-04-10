#!/usr/bin/env python3
"""
Binary chop to find the precise native bbcount where a string first appears in memory.

Uses binary search over the recording timeline: at each midpoint, checks
whether the target string exists anywhere in process memory. Converges on
the exact transition point where the string is first allocated.

Usage:
    wheres_wally_binary_chop.py <mcp_path> <recording_path> <key_path> [search_string]

Default search string: "Here is Wally on stdout"

Outputs:
    FOUND_BBCOUNT=<bbcount where string first appears>
    FOUND_ADDRESS=<memory address of the string>
    Native stack trace at that point
"""
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(sys.argv[1]), 'demos', 'python'))
from undo_mcp import Session

DEFAULT_SEARCH_STRING = "Here is Wally on stdout"


def main():
    if len(sys.argv) < 4:
        print("Usage: wheres_wally_binary_chop.py <mcp_path> <recording_path> <key_path> [search_string]")
        sys.exit(1)

    mcp_path, recording_path, key_path = sys.argv[1:4]
    search_string = sys.argv[4] if len(sys.argv) > 4 else DEFAULT_SEARCH_STRING

    with Session(mcp_path=mcp_path, input_path=recording_path, key_path=key_path) as session:
        print(f'Searching for: "{search_string}"\n')

        state = session.inspection.get_session_state()
        low = state['minBbcount']
        high = state['maxBbcount']
        span = high - low
        print(f"Recording range: {low:,} to {high:,} ({span:,} bbcounts)")

        # Verify preconditions: string absent at start, present at end
        session.navigation.goto_native_bbcount(high)
        end_result = session.memory.search_process_memory(search_string)
        if not end_result.get('found'):
            print("String not found at end of recording — nothing to search for")
            sys.exit(1)

        session.navigation.goto_native_bbcount(low)
        start_result = session.memory.search_process_memory(search_string)
        if start_result.get('found'):
            print("String already present at start of recording — cannot narrow down")
            print(f"FOUND_BBCOUNT={low}")
            print(f"FOUND_ADDRESS={start_result.get('address')}")
            sys.exit(0)

        print(f"Confirmed: absent at start, present at end — binary searching...\n")

        # Binary search for the first bbcount where the string exists
        iteration = 0
        while low < high:
            iteration += 1
            mid = (low + high) // 2

            session.navigation.goto_native_bbcount(mid)
            result = session.memory.search_process_memory(search_string)

            if result.get('found'):
                high = mid
                print(f"  [{iteration:>2}] bbcount {mid:>13,}: FOUND  -> [{low:,}, {mid:,}]")
            else:
                low = mid + 1
                print(f"  [{iteration:>2}] bbcount {mid:>13,}: absent -> [{low:,}, {high:,}]")

        # low == high == first bbcount where string exists
        session.navigation.goto_native_bbcount(low)
        final = session.memory.search_process_memory(search_string)

        print(f"\nConverged after {iteration} iterations")
        print(f"FOUND_BBCOUNT={low}")
        print(f"FOUND_ADDRESS={final.get('address', '?')}")
        print(f"Position: {(low - state['minBbcount']) / span * 100:.1f}% through the recording")

        stack = session.memory.get_native_stack_trace()
        print(f"\nNative stack trace at bbcount {low:,}:")
        print(stack.get('formattedTrace', '(no trace available)'))


if __name__ == "__main__":
    main()
