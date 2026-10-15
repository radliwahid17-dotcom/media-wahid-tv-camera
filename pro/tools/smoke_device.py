#!/usr/bin/env python3
"""Install/launch smoke test. Does not claim camera, media, audio or flip validation."""
import argparse
import json
from pathlib import Path
import subprocess
import time

p = argparse.ArgumentParser()
p.add_argument('--adb', default='adb')
p.add_argument('--apk', default='artifacts/Media-Wahid-TV-Camera-Pro-1.0.2-FLIP.apk')
p.add_argument('--output', default='artifacts/device-smoke.json')
a = p.parse_args()
package = 'tv.mediawahid.camerapro'


def run(*args):
    return subprocess.check_output([a.adb, *args], text=True, stderr=subprocess.STDOUT, timeout=120)


assert run('shell', 'getprop', 'sys.boot_completed').strip() == '1', 'Android has not completed boot'
run('install', '-r', a.apk)
for permission in ['CAMERA', 'RECORD_AUDIO']:
    run('shell', 'pm', 'grant', package, 'android.permission.' + permission)
run('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
run('shell', 'wm', 'dismiss-keyguard')
run('logcat', '-c')
outcomes = []
for i in range(7):
    run('shell', 'am', 'force-stop', package)
    launch = run('shell', 'am', 'start', '-W', '-n', package + '/.MainActivity')
    assert 'Status: ok' in launch, launch
    time.sleep(3)
    pid = run('shell', 'pidof', package).strip()
    assert pid, 'Process exited after launch'
    activity = run('shell', 'dumpsys', 'activity', 'activities')
    assert package + '/.MainActivity' in activity, 'Activity absent'
    log = run('logcat', '-d', '-b', 'crash')
    assert 'FATAL EXCEPTION' not in log, log
    outcomes.append({'iteration': i + 1, 'install_launch': 'passed', 'pid': pid})
result = {
    'device': run('shell', 'getprop', 'ro.product.model').strip(),
    'android_api': run('shell', 'getprop', 'ro.build.version.sdk').strip(),
    'outcomes': outcomes,
    'not_tested': ['photo', 'video', 'flip', 'watermark output', 'microphone continuity', '90 minutes'],
}
Path(a.output).parent.mkdir(parents=True, exist_ok=True)
Path(a.output).write_text(json.dumps(result, indent=2) + '\n')
print(json.dumps(result, indent=2))
