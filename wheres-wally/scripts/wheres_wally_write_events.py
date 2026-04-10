#!/usr/bin/env python3
"""
Decode every write() syscall in a recording.

Scans the undo event log for write events, navigates to each one, reads
the fd and buffer from argument registers, and decodes the buffer contents.

Usage:
    wheres_wally_write_events.py <mcp_path> <recording_path> <key_path> [min_length]

Default min_length: 4  (only show writes longer than this)

Outputs:
    Table of all write events with fd, length, and decoded buffer data.
    Summary of bytes written per file descriptor.
"""
import os
import sys
from collections import defaultdict

sys.path.insert(0, os.path.join(os.path.dirname(sys.argv[1]), 'demos', 'python'))
from undo_mcp import Session

FD_NAMES = {0: 'stdin', 1: 'stdout', 2: 'stderr'}


def decode_hex(hex_data):
    """Decode hex-encoded bytes to a printable string with escape sequences."""
    if not hex_data:
        return ""
    try:
        raw = bytes.fromhex(hex_data.replace(" ", ""))
    except ValueError as e:
        return f"<bad hex: {e}>"

    chars = []
    for b in raw:
        if 32 <= b <= 126:
            chars.append(chr(b))
        elif b == 10:
            chars.append('\\n')
        elif b == 13:
            chars.append('\\r')
        elif b == 9:
            chars.append('\\t')
        else:
            chars.append(f'\\x{b:02x}')
    return ''.join(chars)


def fd_label(fd):
    return FD_NAMES.get(fd, f'fd={fd}')


def main():
    if len(sys.argv) < 4:
        print("Usage: wheres_wally_write_events.py <mcp_path> <recording_path> <key_path> [min_length]")
        sys.exit(1)

    mcp_path, recording_path, key_path = sys.argv[1:4]
    min_length = int(sys.argv[4]) if len(sys.argv) > 4 else 4

    with Session(mcp_path=mcp_path, input_path=recording_path, key_path=key_path) as session:
        state = session.inspection.get_session_state()
        min_bb = state['minBbcount']
        max_bb = state['maxBbcount']
        print(f"Recording range: {min_bb:,} to {max_bb:,}")
        print(f"Scanning for write() calls with length > {min_length}...\n")

        # Phase 1: Collect write events from the undo log
        events = []
        cursor = min_bb

        while cursor < max_bb:
            result = session.call_tool("get_next_undo_event", {
                "bbcount": cursor,
                "event_type": "write",
                "min_extra": min_length + 1  # extra = byte count; > min_length means >= min_length+1
            })

            if not (result.get('success') and result.get('event')):
                break

            ev = result['event']
            events.append((ev['bbcount'], ev['extra']))
            cursor = ev['bbcount'] + 1

            if len(events) % 20 == 0:
                print(f"  Found {len(events)} events so far...")

        print(f"Found {len(events)} write events\n")

        # Phase 2: Navigate to each event and read fd + buffer
        rows = []
        for i, (bbcount, length) in enumerate(events):
            session.navigation.goto_native_bbcount(bbcount)

            # write(int fd, const void *buf, size_t count)
            # x86_64 ABI: RDI=fd, RSI=buf, RDX=count
            regs = session.memory.get_argument_registers()

            if regs['count'] < 3:
                rows.append((bbcount, -1, length, '<insufficient registers>'))
                continue

            fd = int(regs['registers'][0], 16)
            buf_addr = int(regs['registers'][1], 16)

            try:
                mem = session.memory.read_memory(buf_addr, length)
                data = decode_hex(mem.get('data', ''))
            except Exception as e:
                data = f'<error: {e}>'

            rows.append((bbcount, fd, length, data))

            if (i + 1) % 20 == 0:
                print(f"  Decoded {i + 1}/{len(events)} writes...")

        # Phase 3: Results
        print()
        print("=" * 100)
        print(f"{'BBCount':<14} {'FD':<10} {'Bytes':<7} {'Data'}")
        print("-" * 100)

        fd_stats = defaultdict(lambda: {'count': 0, 'bytes': 0})

        for bbcount, fd, length, data in rows:
            fd_stats[fd]['count'] += 1
            fd_stats[fd]['bytes'] += length

            display = data if len(data) <= 68 else data[:65] + "..."
            print(f"{bbcount:<14} {fd_label(fd):<10} {length:<7} {display}")

        print()
        print("Summary:")
        for fd in sorted(fd_stats):
            s = fd_stats[fd]
            print(f"  {fd_label(fd):<10} {s['count']:>4} writes, {s['bytes']:>8,} bytes")
        print(f"  {'total':<10} {len(rows):>4} writes")


if __name__ == "__main__":
    main()
