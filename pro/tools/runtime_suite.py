#!/usr/bin/env python3
"""Run independent Android validation stages and expose a useful CI failure annotation."""
import os
from pathlib import Path
import subprocess
import sys

stages = [
    ('Emulator display budget (camera output unchanged)', ['adb', 'shell', 'wm', 'size', '480x854']),
    ('Emulator display density', ['adb', 'shell', 'wm', 'density', '160']),
    ('Install and seven cold launches', [sys.executable, 'tools/smoke_device.py']),
    ('Original state adapter', [sys.executable, 'tools/device_state_test.py']),
    ('Original logo renderer', [sys.executable, 'tools/logo_device_test.py', '--run-only']),
    ('Disable Wi-Fi', ['adb', 'shell', 'svc', 'wifi', 'disable']),
    ('Disable mobile data', ['adb', 'shell', 'svc', 'data', 'disable']),
    ('Finish instrumentation process', ['adb', 'shell', 'am', 'force-stop', 'tv.mediawahid.camerapro']),
    ('Launch offline camera', ['adb', 'shell', 'am', 'start', '-W', '-n', 'tv.mediawahid.camerapro/.MainActivity']),
    ('Saved photo logos', [sys.executable, 'tools/photo_device_test.py', '--allow-resolution-fallback']),
    ('Continuous video, microphone packets and 20 flips', [sys.executable, 'tools/video_device_test.py']),
]
Path('artifacts').mkdir(exist_ok=True)
for index, (name, command) in enumerate(stages, 1):
    print(name, flush=True)
    try:
        result = subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=1200)
        output, status = result.stdout, result.returncode
    except subprocess.TimeoutExpired as error:
        output, status = str(error), 1
    Path(f'artifacts/runtime-stage-{index}.log').write_text(output)
    print(output, flush=True)
    if status:
        tail = '\n'.join(output.splitlines()[-60:])
        if os.environ.get('GITHUB_ACTIONS') == 'true':
            tail = tail.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
            print(f'::error title={name}::{tail}', flush=True)
        raise SystemExit(status)
