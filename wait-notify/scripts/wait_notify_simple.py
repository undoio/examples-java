#!/usr/bin/env python3
"""
Find a stuck wait/notify thread and where notify() was missed.

This script analyses an Undo recording of WaitNotifyDemo to:
1. Find all Consumer threads stuck in wait() at end of recording
2. Determine which has been waiting longest (the stuck one)
3. Reverse-search to find the exact point the producer skipped notify()

Outputs:
  STUCK_THREAD=Consumer-N
  SKIP_BBCOUNT=<bbcount where notify was skipped>
"""
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(sys.argv[1]), 'demos', 'python'))
from undo_mcp import Session

# Line number where producer skips notify (update if WaitNotifyDemo.java changes)
PRODUCER_SKIP_LINE = 239


def main():
    if len(sys.argv) < 4:
        print("Usage: wait_notify_simple.py <mcp_path> <recording_path> <key_path>")
        sys.exit(1)

    mcp_path, recording_path, key_path = sys.argv[1:4]

    with Session(mcp_path=mcp_path, input_path=recording_path, key_path=key_path) as session:
        # Go to end and find Consumer threads in wait()
        session.navigation.goto_end()
        threads = session.inspection.list_threads()

        waiting_consumers = []
        wait_line = None

        for thread in threads.get('threads', []):
            name = thread['threadName']
            if thread.get('threadGroup') == 'system' or not name.startswith('Consumer-'):
                continue

            stack = session.inspection.get_thread_stack(threadId=thread['threadId'], maxFrames=10)
            frames = stack.get('frames', [])

            # Java 21+ reports the native method as 'wait0' instead of 'wait'
            if frames and frames[0].get('methodName') in ('wait', 'wait0'):
                for f in frames:
                    if 'Consumer' in f.get('className', '') and f.get('methodName') == 'run':
                        wait_line = f.get('lineNumber')
                        break
                waiting_consumers.append(thread)

        if not waiting_consumers:
            print("No waiting consumers found")
            sys.exit(1)

        # Set breakpoint on wait line and reverse to find when each entered wait()
        session.breakpoints.set(className="io.undo.demos.WaitNotifyDemo$Consumer", line=wait_line)
        session.navigation.goto_end()

        thread_wait_times = {}
        threads_to_find = {t['threadId']: t['threadName'] for t in waiting_consumers}

        while True:
            try:
                session.execution.reverse_continue()
                result = session.execution.wait_for_stop()

                state = session.inspection.get_session_state()
                if state.get('atStartOfRecording'):
                    break

                tid = result.get('threadId')
                if tid in threads_to_find and tid not in thread_wait_times:
                    thread_wait_times[tid] = state.get('bbcount')
                    if len(thread_wait_times) == len(threads_to_find):
                        break
            except:
                break

        session.breakpoints.clear_all()

        # Threads not found were waiting from recording start
        for tid in threads_to_find:
            if tid not in thread_wait_times:
                thread_wait_times[tid] = 0

        # Thread waiting longest = lowest bbcount = stuck
        stuck_tid = min(thread_wait_times, key=thread_wait_times.get)
        stuck_name = threads_to_find[stuck_tid]

        print(f"STUCK_THREAD={stuck_name}")

        # Find where producer skipped notify for this thread
        session.navigation.goto_end()
        session.breakpoints.set(
            className="io.undo.demos.WaitNotifyDemo$Producer", line=PRODUCER_SKIP_LINE)

        skip_bbcount = None

        while True:
            try:
                session.execution.reverse_continue()
                result = session.execution.wait_for_stop()

                state = session.inspection.get_session_state()
                if state.get('atStartOfRecording'):
                    break

                # Get producer's consumer field
                producer_stack = session.inspection.get_thread_stack(maxFrames=5)
                for i, f in enumerate(producer_stack.get('frames', [])):
                    if 'Producer' in f.get('className', '') and f.get('methodName') == 'run':
                        variables = session.inspection.get_variables(frameId=i)
                        for var in variables.get('variables', []):
                            if var.get('name') == 'this':
                                producer_this = var.get('objectId')
                                consumer_field = session.inspection.get_field_value(
                                    objectId=producer_this, fieldName='consumer')
                                producer_consumer_id = consumer_field.get('valueObjectId')
                                break
                        break

                # Get stuck consumer's object id
                consumer_stack = session.inspection.get_thread_stack(
                    threadId=stuck_tid, maxFrames=10)
                for i, f in enumerate(consumer_stack.get('frames', [])):
                    if 'Consumer' in f.get('className', '') and f.get('methodName') == 'run':
                        variables = session.inspection.get_variables(
                            threadId=stuck_tid, frameId=i)
                        for var in variables.get('variables', []):
                            if var.get('name') == 'this':
                                stuck_consumer_id = var.get('objectId')
                                break
                        break

                # Check if this producer is paired with our stuck consumer
                if str(producer_consumer_id) == str(stuck_consumer_id):
                    skip_bbcount = state.get('bbcount')
                    break
            except:
                break

        session.breakpoints.clear_all()

        if skip_bbcount is None:
            print("SKIP_BBCOUNT=NOT_FOUND")
            sys.exit(1)

        print(f"SKIP_BBCOUNT={skip_bbcount}")


if __name__ == "__main__":
    main()
