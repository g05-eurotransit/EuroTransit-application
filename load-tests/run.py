#!/usr/bin/env python3
"""Run k6, preserve attributable results even on failure, never print credentials."""
import json
import os
from pathlib import Path
import subprocess
import signal
import threading
import sys


def main():
    directory = Path(os.environ.get('OUTPUT_DIR', '/results'))
    directory.mkdir(parents=True, exist_ok=True)
    os.environ['OUTPUT_DIR'] = str(directory)
    script_arg = Path(sys.argv[1] if len(sys.argv) > 1 else 'checkout.js')
    script = script_arg if script_arg.is_absolute() else Path(__file__).parent / script_arg
    rows = []
    with (directory / 'orders.jsonl').open('w') as ledger:
        process = subprocess.Popen(['k6', 'run', '--log-format', 'raw', '--log-output', 'stdout', str(script)], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        def stop(signum, frame):
            process.terminate()
            timer = threading.Timer(5, lambda: process.kill() if process.poll() is None else None)
            timer.daemon = True
            timer.start()
        signal.signal(signal.SIGTERM, stop)
        signal.signal(signal.SIGINT, stop)
        for line in process.stdout:
            # k6 error messages contain no request bodies; runner never logs environment.
            print(line, end='', flush=True)
            if 'ET_RESULT ' in line:
                try:
                    event = json.loads(line.split('ET_RESULT ', 1)[1])
                except json.JSONDecodeError:
                    continue
                if event.get('type') == 'order_result':
                    ledger.write(json.dumps(event) + '\n'); ledger.flush(); rows.append(event)
        code = process.wait()
    summary_path = directory / 'summary.json'
    summary = json.loads(summary_path.read_text()) if summary_path.exists() else {'runId': os.environ.get('RUN_ID'), 'passed': False, 'reason': 'k6_did_not_complete'}
    expected = int(os.environ.get('MAX_ATTEMPTS', '20'))
    if len(rows) != expected:
        summary.update(passed=False, reason='incomplete_order_ledger')
    if os.environ.get('SCENARIO') == 'contention' and sum(r.get('outcome') == 'confirmed' for r in rows) != 1:
        summary.update(passed=False, reason='contention_requires_exactly_one_winner')
    if os.environ.get('SCENARIO') in ('ramp', 'spike'):
        summary['overloadDemonstrated'] = any(r.get('outcome') == 'overload_refusal' for r in rows)
        if os.environ.get('EXPECT_OVERLOAD') == 'true' and not summary['overloadDemonstrated']:
            summary.update(passed=False, reason='expected_overload_not_observed')
    if os.environ.get('SCENARIO') in ('ramp', 'spike') and not any(r.get('phase') == 'recovery' and r.get('outcome') == 'confirmed' for r in rows):
        summary.update(passed=False, reason='no_confirmed_recovery_checkout')
    summary_path.write_text(json.dumps(summary, indent=2) + '\n')
    if not summary.get('passed'):
        code = code or 1
    print('ET_RESULT ' + json.dumps({'type': 'runner_finished', 'runId': os.environ.get('RUN_ID'), 'passed': code == 0, 'exitCode': code}), flush=True)
    return code

if __name__ == '__main__':
    sys.exit(main())
