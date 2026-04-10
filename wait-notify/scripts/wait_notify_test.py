#!/usr/bin/env python3
"""
Python script to find threads stuck on wait() and identify the one waiting longest.

This script:
1. Goes to the end of the recording
2. Lists all threads and finds Consumer threads with wait() at top of stack
3. Sets a breakpoint on the Consumer.wait() line
4. Reverse continues repeatedly to find when each thread entered wait()
5. Handles threads that were already waiting when recording started
6. Reports which thread has been waiting longest (lowest bbcount or never hit bp)
7. Verifies the stuck consumer has skipFlag=true
8. Finds the exact skip point in the producer
"""
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(sys.argv[1]), 'demos', 'python'))
from undo_mcp import Session

# Line numbers in WaitNotifyDemo.java (update if source changes)
CONSUMER_WAIT_LINE = 172  # this.wait() in Consumer.run - where consumer waits for work
CONSUMER_NOTIFY_LINE = 147  # this.notify() in sendWork() - where producer wakes consumer
PRODUCER_SKIP_LINE = 239  # skippedCount++ - where producer skips notify


def main():
    if len(sys.argv) < 4:
        print("Usage: wait_notify_test.py <mcp_path> <recording_path> <key_path>")
        sys.exit(1)

    mcp_path, recording_path, key_path = sys.argv[1:4]

    with Session(mcp_path=mcp_path, input_path=recording_path, key_path=key_path, debug=False) as session:
            print("Starting wait/notify analysis...\n")

            # Step 1: Go to the end of the recording
            print("Step 1: Going to end of recording...")
            session.navigation.goto_end()
            state = session.inspection.get_session_state()
            end_bbcount = state.get('bbcount')
            print(f"  At bbcount: {end_bbcount}")

            # Step 2: List all threads and find Consumer threads with wait() at top of stack
            print("\nStep 2: Finding Consumer threads with wait() at top of stack...")
            threads_result = session.inspection.list_threads()
            print(f"  Total threads: {threads_result.get('threadCount')}")

            waiting_consumers = []
            wait_line_number = None

            for thread in threads_result.get('threads', []):
                thread_id = thread['threadId']
                thread_name = thread['threadName']
                thread_group = thread.get('threadGroup', 'unknown')

                # Skip system threads (JVM internal threads are in the 'system' thread group)
                if thread_group == 'system':
                    print(f"  Skipping system thread: {thread_name} (group={thread_group})")
                    continue

                # Only look at Consumer threads
                if not thread_name.startswith('Consumer-'):
                    continue

                # Get stack for this thread
                try:
                    stack = session.inspection.get_thread_stack(threadId=thread_id, maxFrames=10)
                    frames = stack.get('frames', [])

                    if frames:
                        top_frame = frames[0]
                        method_name = top_frame.get('methodName', '')

                        # Check if wait() is at top of stack
                        # Java 21+ reports the native method as 'wait0' instead of 'wait'
                        if method_name in ('wait', 'wait0'):
                            # Find the Consumer.run frame to get the wait line number
                            for frame in frames:
                                if 'Consumer' in frame.get('className', '') and frame.get('methodName') == 'run':
                                    wait_line_number = frame.get('lineNumber')
                                    break

                            print(f"  Found waiting consumer: {thread_name} (id={thread_id})")
                            waiting_consumers.append({
                                'threadId': thread_id,
                                'threadName': thread_name,
                            })
                except Exception as e:
                    print(f"  Could not get stack for {thread_name}: {e}")

            if not waiting_consumers:
                print("No Consumer threads found with wait() at top of stack!")
                sys.exit(1)

            print(f"\n  Found {len(waiting_consumers)} Consumer threads waiting on wait()")

            if wait_line_number is None:
                wait_line_number = CONSUMER_WAIT_LINE
                print(f"  Using default wait line number: {wait_line_number}")
            else:
                print(f"  Wait line number in Consumer.run: {wait_line_number}")

            # Step 3: Set breakpoint on the wait() line and reverse continue to find entry times
            print("\nStep 3: Finding when each Consumer entered wait()...")

            # Set breakpoint on the wait line in Consumer
            bp_result = session.breakpoints.set(
                className="io.undo.demos.WaitNotifyDemo$Consumer",
                line=wait_line_number
            )
            print(f"  Set breakpoint: {bp_result}")

            # Track when each thread entered wait() (threadId -> bbcount)
            thread_wait_entry = {}
            threads_to_find = {wc['threadId']: wc['threadName'] for wc in waiting_consumers}

            # Go back to end to start reverse search
            session.navigation.goto_end()

            # Reverse continue until we hit start of recording
            max_iterations = 5000  # Safety limit
            iteration = 0

            while iteration < max_iterations:
                iteration += 1

                try:
                    session.execution.reverse_continue()
                    result = session.execution.wait_for_stop()

                    stop_reason = result.get('reason', '')
                    if 'start' in stop_reason.lower() or 'terminus' in stop_reason.lower():
                        print("  Reached start of recording")
                        break

                    current_thread_id = result.get('threadId')
                    state = session.inspection.get_session_state()
                    bbcount = state.get('bbcount')

                    if state.get('atStartOfRecording'):
                        print("  Reached start of recording (atStartOfRecording=true)")
                        break

                    # Record only the FIRST hit for each consumer (going backwards)
                    # This is their LAST wait() entry before the end of recording
                    if current_thread_id in threads_to_find:
                        thread_name = threads_to_find[current_thread_id]
                        if current_thread_id not in thread_wait_entry:
                            thread_wait_entry[current_thread_id] = {
                                'bbcount': bbcount,
                                'threadName': thread_name
                            }
                            print(f"  {thread_name}: last entered wait() at bbcount {bbcount}")
                            if len(thread_wait_entry) == len(threads_to_find):
                                print("  Found all consumers, stopping search")
                                break

                    if iteration % 100 == 0:
                        print(f"  ... iteration {iteration}, found {len(thread_wait_entry)} threads so far")

                except Exception as e:
                    error_msg = str(e).lower()
                    if 'start' in error_msg or 'terminus' in error_msg or 'beginning' in error_msg:
                        print("  Reached start of recording")
                        break
                    print(f"  Error during reverse continue: {e}")
                    break

            session.breakpoints.clear_all()

            # Step 4: Identify threads that were waiting from the start (never hit bp)
            print("\nStep 4: Checking for threads waiting since recording start...")

            for thread_id, thread_name in list(threads_to_find.items()):
                if thread_id not in thread_wait_entry:
                    print(f"  {thread_name}: was already waiting when recording started")
                    thread_wait_entry[thread_id] = {
                        'bbcount': 0,
                        'threadName': thread_name,
                        'from_start': True
                    }

            # Step 5: Find thread waiting longest and show summary
            print("\nStep 5: Identifying thread waiting longest...")

            if thread_wait_entry:
                # Go to end to inspect skipFlag for all consumers
                session.navigation.goto_end()

                # Sort by bbcount - lowest means waiting longest
                sorted_threads = sorted(thread_wait_entry.items(), key=lambda x: x[1]['bbcount'])

                # Print summary of all consumers with their skipFlag
                print("\n  Consumer summary (sorted by wait time, longest first):")
                for thread_id, info in sorted_threads:
                    skip_flag_value = "?"
                    try:
                        stack = session.inspection.get_thread_stack(threadId=thread_id)
                        frames = stack.get('frames', [])
                        for i, frame in enumerate(frames):
                            if 'Consumer' in frame.get('className', '') and frame.get('methodName') == 'run':
                                variables = session.inspection.get_variables(threadId=thread_id, frameId=i)
                                for var in variables.get('variables', []):
                                    if var.get('name') == 'this':
                                        this_obj_id = var.get('objectId')
                                        if this_obj_id:
                                            skip_flag = session.inspection.get_field_value(objectId=this_obj_id, fieldName='skipFlag')
                                            skip_flag_value = skip_flag.get('value')
                                        break
                                break
                    except Exception:
                        pass

                    wait_time = end_bbcount - info['bbcount'] if not info.get('from_start') else "from start"
                    print(f"    {info['threadName']}: wait_time={wait_time}, skipFlag={skip_flag_value}")

                longest_thread_id, longest_info = sorted_threads[0]

                if longest_info.get('from_start'):
                    print(f"\nThread waiting longest: {longest_info['threadName']}")
                    print(f"  Thread ID: {longest_thread_id}")
                    print(f"  Was already waiting when recording started")
                else:
                    print(f"\nThread waiting longest: {longest_info['threadName']}")
                    print(f"  Thread ID: {longest_thread_id}")
                    print(f"  Entered wait() at bbcount: {longest_info['bbcount']}")
                    print(f"  End of recording bbcount: {end_bbcount}")
                    print(f"  Time spent waiting: {end_bbcount - longest_info['bbcount']} bbcounts")

                # Step 6: Verify this is the stuck consumer by checking skipFlag
                print("\nStep 6: Verifying stuck consumer has skipFlag=true...")

                verified = False
                try:
                    stack = session.inspection.get_thread_stack(threadId=longest_thread_id)
                    frames = stack.get('frames', [])

                    for i, frame in enumerate(frames):
                        if 'Consumer' in frame.get('className', '') and frame.get('methodName') == 'run':
                            print(f"  Found Consumer.run at frame {i}")

                            variables = session.inspection.get_variables(
                                threadId=longest_thread_id,
                                frameId=i
                            )
                            var_list = variables.get('variables', [])

                            for var in var_list:
                                if var.get('name') == 'this':
                                    this_object_id = var.get('objectId')
                                    if this_object_id:
                                        skip_flag = session.inspection.get_field_value(
                                            objectId=this_object_id,
                                            fieldName='skipFlag'
                                        )
                                        skip_value = skip_flag.get('value')
                                        print(f"  Consumer skipFlag = {skip_value}")

                                        if skip_value in (True, 'true', 1, '1'):
                                            print("  Verified: stuck consumer has skipFlag=true")
                                            verified = True
                                        else:
                                            print("  Unexpected: skipFlag is not true")

                                        consumer_id = session.inspection.get_field_value(
                                            objectId=this_object_id,
                                            fieldName='id'
                                        )
                                        print(f"  Consumer id = {consumer_id.get('value')}")
                                    break
                            break

                except Exception as e:
                    print(f"  Error verifying consumer: {e}")

                if not verified:
                    print("\nTEST FAILED: Could not verify stuck consumer has skipFlag=true")
                    sys.exit(1)

                # Step 7: Find the exact skip point by setting breakpoint on notify decision
                print("\nStep 7: Finding exact skip point in producer...")

                session.navigation.goto_end()

                bp_result = session.breakpoints.set(
                    className="io.undo.demos.WaitNotifyDemo$Producer",
                    line=PRODUCER_SKIP_LINE
                )
                print(f"  Set breakpoint on Producer line {PRODUCER_SKIP_LINE}: {bp_result}")

                found_skip = False
                max_iterations = 10000
                iteration = 0

                while iteration < max_iterations:
                    iteration += 1

                    try:
                        session.execution.reverse_continue()
                        result = session.execution.wait_for_stop()

                        stop_reason = result.get('reason', '')
                        if 'start' in stop_reason.lower() or 'terminus' in stop_reason.lower():
                            print("  Reached start of recording without finding skip point")
                            break

                        state = session.inspection.get_session_state()
                        if state.get('atStartOfRecording'):
                            print("  Reached start of recording without finding skip point")
                            break

                        bbcount = state.get('bbcount')

                        # Get producer's 'this.consumer' objectId from producer's stack
                        producer_stack = session.inspection.get_thread_stack(maxFrames=5)
                        producer_frames = producer_stack.get('frames', [])

                        producer_consumer_obj_id = None
                        producer_this_id = None
                        for i, frame in enumerate(producer_frames):
                            if 'Producer' in frame.get('className', '') and frame.get('methodName') == 'run':
                                variables = session.inspection.get_variables(frameId=i)
                                for var in variables.get('variables', []):
                                    if var.get('name') == 'this':
                                        producer_this_id = var.get('objectId')
                                        if producer_this_id:
                                            consumer_field = session.inspection.get_field_value(
                                                objectId=producer_this_id,
                                                fieldName='consumer'
                                            )
                                            producer_consumer_obj_id = consumer_field.get('valueObjectId')
                                        break
                                break

                        if producer_consumer_obj_id is None:
                            continue

                        # Get stuck consumer's 'this' objectId from its current stack
                        consumer_stack = session.inspection.get_thread_stack(
                            threadId=longest_thread_id, maxFrames=10
                        )
                        consumer_frames = consumer_stack.get('frames', [])

                        stuck_consumer_obj_id = None
                        for i, frame in enumerate(consumer_frames):
                            if 'Consumer' in frame.get('className', '') and frame.get('methodName') == 'run':
                                variables = session.inspection.get_variables(
                                    threadId=longest_thread_id, frameId=i
                                )
                                for var in variables.get('variables', []):
                                    if var.get('name') == 'this':
                                        stuck_consumer_obj_id = var.get('objectId')
                                        break
                                break

                        if stuck_consumer_obj_id is None:
                            continue

                        if iteration % 500 == 0:
                            print(f"  Debug at iter {iteration}: producer_consumer_obj_id={producer_consumer_obj_id}, stuck_consumer_obj_id={stuck_consumer_obj_id}")

                        if str(producer_consumer_obj_id) == str(stuck_consumer_obj_id):
                            # Match! This producer is assigned to our stuck consumer
                            skip_flag_field = session.inspection.get_field_value(
                                objectId=producer_this_id,
                                fieldName='skipFlag'
                            )
                            skip_flag_value = skip_flag_field.get('value')

                            if skip_flag_value in (True, 'true', 1, '1'):
                                print(f"\n  Found skip point at bbcount {bbcount}")
                                print(f"    Producer skipFlag = {skip_flag_value}")

                                skipped_count_field = session.inspection.get_field_value(
                                    objectId=producer_this_id,
                                    fieldName='skippedCount'
                                )
                                print(f"    Producer skippedCount = {skipped_count_field.get('value')}")

                                found_skip = True
                                break
                            else:
                                print(f"\n  ERROR at bbcount {bbcount}: objectIds match but skipFlag={skip_flag_value}")
                                print("  This means producer notified, but consumer is stuck - analysis error!")
                                break

                        if iteration <= 10 or iteration % 500 == 0:
                            print(f"  iter {iteration}: producer->consumer={producer_consumer_obj_id}, stuck_consumer={stuck_consumer_obj_id}")

                    except Exception as e:
                        error_msg = str(e).lower()
                        if 'start' in error_msg or 'terminus' in error_msg or 'beginning' in error_msg:
                            print("  Reached start of recording")
                            break
                        print(f"  Error during search: {e}")
                        break

                session.breakpoints.clear_all()

                if not found_skip:
                    print("\nTEST FAILED: Could not find skip point")
                    sys.exit(1)

            print("\nWait/notify analysis complete!")


if __name__ == "__main__":
    main()
