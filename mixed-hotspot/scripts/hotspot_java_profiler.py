#!/usr/bin/env python3
"""
Java-level sampling profiler using time-travel debugging.

Samples Java stack traces at evenly-spaced wall-clock times across a
recording and reports which application methods consume the most time.

Uses wall-clock time sampling (not bbcount) to avoid bias toward methods
that execute more bytecode instructions vs methods with JIT-compiled tight loops.

Automatically distinguishes application code from JDK/library code.

Usage:
    hotspot_java_profiler.py <mcp_path> <recording_path> <key_path> [num_samples]

Default num_samples: 50

Outputs:
    - Application method time breakdown (inclusive: anywhere in stack)
    - Top-of-stack analysis (exclusive: what's actually executing)
    - Callee breakdown for each application method
    - Example call chains
"""
import os
import sys
from collections import defaultdict

sys.path.insert(0, os.path.join(os.path.dirname(sys.argv[1]), 'demos', 'python'))
from undo_mcp import Session

# Packages considered "system" — everything else is application code
SYSTEM_PREFIXES = (
    'java.', 'javax.', 'jdk.', 'sun.', 'com.sun.',
    'org.ietf.', 'org.w3c.', 'org.xml.',
)


def is_app_method(qualified_name):
    """Check if a fully-qualified method key (class.method) is application code."""
    return not any(qualified_name.startswith(p) for p in SYSTEM_PREFIXES)


def fmt_frame(frame):
    cls = frame.get('className', '?')
    # Shorten common JDK prefixes for readability
    for prefix in ('java.lang.', 'java.util.', 'java.security.', 'java.io.'):
        if cls.startswith(prefix):
            cls = cls[len(prefix):]
            break
    return f"{cls}.{frame.get('methodName', '?')}:{frame.get('lineNumber', '?')}"


def main():
    if len(sys.argv) < 4:
        print("Usage: hotspot_java_profiler.py <mcp_path> <recording_path> <key_path> [num_samples]")
        sys.exit(1)

    mcp_path, recording_path, key_path = sys.argv[1:4]
    num_samples = int(sys.argv[4]) if len(sys.argv) > 4 else 50

    with Session(mcp_path=mcp_path, input_path=recording_path, key_path=key_path) as session:
        state = session.inspection.get_session_state()
        min_time = state['minUtcTime']  # microseconds since epoch
        max_time = state['maxUtcTime']
        duration_s = (max_time - min_time) / 1_000_000
        print(f"Recording duration: {duration_s:.1f}s")
        print(f"Collecting {num_samples} Java stack samples (wall-clock time sampling)...\n")

        # Evenly-spaced wall-clock time sample points
        step = (max_time - min_time) // (num_samples + 1)
        sample_times = [min_time + step * (i + 1) for i in range(num_samples)]

        # Phase 1: Collect samples
        samples = []
        for i, t_us in enumerate(sample_times):
            try:
                session.navigation.goto_time(t_us // 1000)  # API takes milliseconds
                stack = session.inspection.get_thread_stack(maxFrames=64)
                frames = stack.get('frames', [])
                samples.append({'time_us': t_us, 'frames': frames})
            except Exception as e:
                samples.append({'time_us': t_us, 'frames': [], 'error': str(e)})

            if (i + 1) % 10 == 0:
                print(f"  Collected {i + 1}/{num_samples} samples...")

        valid_samples = [s for s in samples if s['frames']]
        total = len(valid_samples)
        print(f"\nCollected {total} valid samples out of {num_samples}\n")

        if total == 0:
            print("No valid samples — nothing to analyse")
            sys.exit(1)

        # Phase 2: Analyse

        # Inclusive counts: method appears anywhere in stack
        inclusive = defaultdict(int)
        # Exclusive counts: method is at top of stack (actually executing)
        exclusive = defaultdict(int)
        # Callee map: app_method -> {callee -> count}
        callees = defaultdict(lambda: defaultdict(int))
        # Example stacks per app method (keep first 2)
        examples = defaultdict(list)

        for sample in valid_samples:
            frames = sample['frames']
            seen_in_sample = set()

            for i, frame in enumerate(frames):
                cls = frame.get('className', '')
                method = frame.get('methodName', '')
                key = f"{cls}.{method}"

                # Inclusive: count each method once per sample
                if key not in seen_in_sample:
                    inclusive[key] += 1
                    seen_in_sample.add(key)

                # Exclusive: top of stack
                if i == 0:
                    exclusive[key] += 1

                # For app classes, track what they call
                if is_app_method(key) and i > 0:
                    callee = frames[i - 1]
                    callee_key = f"{callee.get('className', '?')}.{callee.get('methodName', '?')}"
                    callees[key][callee_key] += 1

                    # Stash example
                    if len(examples[key]) < 2:
                        examples[key].append(sample)

        # Phase 3: Report

        # Application methods only
        app_methods = {k: v for k, v in inclusive.items() if is_app_method(k)}

        print("=" * 70)
        print("APPLICATION METHOD TIME (inclusive — anywhere in stack)")
        print("=" * 70)
        print()

        for method, count in sorted(app_methods.items(), key=lambda x: -x[1]):
            pct = count / total * 100
            bar = '#' * int(pct / 2)
            print(f"  {method:<45} {count:>4}/{total}  ({pct:5.1f}%)  {bar}")

            # Show callee breakdown
            if method in callees:
                for callee, cc in sorted(callees[method].items(), key=lambda x: -x[1])[:5]:
                    cpct = cc / count * 100
                    print(f"    -> {callee:<41} {cc:>4} ({cpct:5.1f}%)")
            print()

        print("=" * 70)
        print("TOP-OF-STACK (exclusive — what's actively executing)")
        print("=" * 70)
        print()

        for method, count in sorted(exclusive.items(), key=lambda x: -x[1])[:20]:
            pct = count / total * 100
            bar = '#' * int(pct / 2)
            tag = " [app]" if is_app_method(method) else ""
            print(f"  {method:<45} {count:>4}/{total}  ({pct:5.1f}%)  {bar}{tag}")

        print()
        print("=" * 70)
        print("EXAMPLE CALL CHAINS")
        print("=" * 70)

        for method in sorted(app_methods, key=lambda k: -app_methods[k]):
            if method not in examples:
                continue
            print(f"\n  {method}:")
            for sample in examples[method][:2]:
                frames = sample['frames']
                elapsed = (sample['time_us'] - min_time) / 1_000_000
                print(f"    t={elapsed:.2f}s:")
                for j, f in enumerate(frames[:8]):
                    prefix = "    -> " if j == 0 else "       "
                    print(f"{prefix}{fmt_frame(f)}")
                if len(frames) > 8:
                    print(f"       ... ({len(frames) - 8} more frames)")


if __name__ == "__main__":
    main()
