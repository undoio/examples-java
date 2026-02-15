#!/usr/bin/env python3
"""
Native-level sampling profiler using time-travel debugging.

Samples native (C/C++) stack traces at evenly-spaced points across a recording
and reports which native functions and modules consume the most execution time.

Useful for understanding what the JVM is actually doing under the hood:
JIT-compiled code, interpreter, GC, syscalls, etc.

Usage:
    hotspot_native_profiler.py <mcp_path> <recording_path> <key_path> [num_samples]

Default num_samples: 50

Outputs:
    - Top native functions by frequency (exclusive: top-of-stack)
    - Module breakdown (which shared libraries are executing)
    - Function frequency (inclusive: anywhere in stack)
"""
import os
import re
import sys
from collections import defaultdict

sys.path.insert(0, os.path.join(os.path.dirname(sys.argv[1]), 'demos', 'python'))
from undo_mcp import Session


def short_module(name):
    """Extract just the library name from a full path."""
    if not name or name == '<unknown>':
        return '<unknown>'
    return name.rsplit('/', 1)[-1] if '/' in name else name


def frame_name(frame):
    """Best available name for a frame.

    The 'description' field (from StackFrameInfo.toString()) resolves JIT-compiled
    Java frames to 'Java: class.method():line', while 'functionName' only has the
    native symbol (or a raw hex address for JIT code).  Prefer description when it
    contains Java info.
    """
    desc = frame.get('description', '')

    # description format: "0x00007f...: Java: class/method():line"
    # or                  "0x00007f...: nativeFunc+0x1a (module.so)"
    java_match = re.search(r'Java: (.+)', desc)
    if java_match:
        return java_match.group(0)  # e.g. "Java: io/undo/demos/MixedHotspot.run():34"

    func = frame.get('functionName', '') or ''
    if func and func != '<unknown>':
        return func

    return desc.split(': ', 1)[-1] if ': ' in desc else '<unknown>'


def frame_module(frame):
    """Best available module name for a frame."""
    desc = frame.get('description', '')
    if 'Java: ' in desc:
        return '[jit]'

    mod = frame.get('moduleName', '')
    if not mod or mod == '<unknown>':
        return '<unknown>'
    return mod.rsplit('/', 1)[-1] if '/' in mod else mod


def main():
    if len(sys.argv) < 4:
        print("Usage: hotspot_native_profiler.py <mcp_path> <recording_path> <key_path> [num_samples]")
        sys.exit(1)

    mcp_path, recording_path, key_path = sys.argv[1:4]
    num_samples = int(sys.argv[4]) if len(sys.argv) > 4 else 50

    with Session(mcp_path=mcp_path, input_path=recording_path, key_path=key_path) as session:
        state = session.inspection.get_session_state()
        min_bb = state['minBbcount']
        max_bb = state['maxBbcount']
        print(f"Recording range: {min_bb:,} to {max_bb:,} ({max_bb - min_bb:,} bbcounts)")
        print(f"Collecting {num_samples} native stack samples...\n")

        step = (max_bb - min_bb) // (num_samples + 1)
        sample_points = [min_bb + step * (i + 1) for i in range(num_samples)]

        # Phase 1: Collect native samples
        samples = []
        for i, bbcount in enumerate(sample_points):
            try:
                session.navigation.goto_native_bbcount(bbcount)
                stack = session.memory.get_native_stack_trace(maxFrames=64)
                frames = stack.get('frames', [])
                samples.append({'bbcount': bbcount, 'frames': frames})
            except Exception as e:
                samples.append({'bbcount': bbcount, 'frames': [], 'error': str(e)})

            if (i + 1) % 10 == 0:
                print(f"  Collected {i + 1}/{num_samples} samples...")

        valid_samples = [s for s in samples if s['frames']]
        total = len(valid_samples)
        print(f"\nCollected {total} valid samples out of {num_samples}\n")

        if total == 0:
            print("No valid samples — nothing to analyse")
            sys.exit(1)

        # Phase 2: Analyse

        # Exclusive: function at top of stack
        exclusive = defaultdict(int)
        # Inclusive: function anywhere in stack
        inclusive = defaultdict(int)
        # Module counts (exclusive — where is the CPU actually spent?)
        module_exclusive = defaultdict(int)
        # Module counts (inclusive)
        module_inclusive = defaultdict(int)
        # Caller -> callee edges
        call_edges = defaultdict(lambda: defaultdict(int))

        for sample in valid_samples:
            frames = sample['frames']
            seen_funcs = set()
            seen_mods = set()

            for i, frame in enumerate(frames):
                func = frame_name(frame)
                mod = frame_module(frame)

                # Inclusive (once per sample)
                if func not in seen_funcs:
                    inclusive[func] += 1
                    seen_funcs.add(func)
                if mod not in seen_mods:
                    module_inclusive[mod] += 1
                    seen_mods.add(mod)

                # Exclusive (top of stack only)
                if i == 0:
                    exclusive[func] += 1
                    module_exclusive[mod] += 1

                # Call edges: caller (deeper frame) -> callee (shallower frame)
                if i > 0:
                    caller = frame_name(frame)
                    callee = frame_name(frames[i - 1])
                    call_edges[caller][callee] += 1

        # Phase 3: Report

        print("=" * 78)
        print("MODULE BREAKDOWN (exclusive — where CPU is actually spent)")
        print("=" * 78)
        print()
        for mod, count in sorted(module_exclusive.items(), key=lambda x: -x[1]):
            pct = count / total * 100
            bar = '#' * int(pct / 2)
            print(f"  {mod:<35} {count:>4}/{total}  ({pct:5.1f}%)  {bar}")

        print()
        print("=" * 78)
        print("TOP NATIVE FUNCTIONS (exclusive — top of stack)")
        print("=" * 78)
        print()
        for func, count in sorted(exclusive.items(), key=lambda x: -x[1])[:25]:
            pct = count / total * 100
            bar = '#' * int(pct / 2)
            print(f"  {func:<55} {count:>4} ({pct:5.1f}%)  {bar}")

        print()
        print("=" * 78)
        print("TOP NATIVE FUNCTIONS (inclusive — anywhere in stack)")
        print("=" * 78)
        print()
        for func, count in sorted(inclusive.items(), key=lambda x: -x[1])[:25]:
            pct = count / total * 100
            bar = '#' * int(pct / 2)
            print(f"  {func:<55} {count:>4} ({pct:5.1f}%)  {bar}")

        # Show interesting call relationships for the top exclusive functions
        print()
        print("=" * 78)
        print("CALL RELATIONSHIPS (top functions and their callers/callees)")
        print("=" * 78)

        top_funcs = [f for f, _ in sorted(exclusive.items(), key=lambda x: -x[1])[:10]]
        for func in top_funcs:
            if exclusive[func] < 2:
                continue
            print(f"\n  {func} ({exclusive[func]} exclusive samples):")

            # What does it call?
            if func in call_edges:
                for callee, count in sorted(call_edges[func].items(), key=lambda x: -x[1])[:3]:
                    print(f"    calls -> {callee} ({count}x)")

            # What calls it?
            callers = [(caller, edges[func]) for caller, edges in call_edges.items() if func in edges]
            callers.sort(key=lambda x: -x[1])
            for caller, count in callers[:3]:
                print(f"    called by <- {caller} ({count}x)")


if __name__ == "__main__":
    main()
