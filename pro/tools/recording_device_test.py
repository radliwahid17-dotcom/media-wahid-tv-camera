#!/usr/bin/env python3
"""Build a same-signer instrumentation harness; run on a dedicated test device only."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import zipfile

root = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser()
p.add_argument('--adb', default='adb')
p.add_argument('--build-only', action='store_true')
p.add_argument('--harness', type=Path, help='Already compiled, same-signer harness APK from the build job')
p.add_argument('--apk', type=Path, default=root / 'artifacts/Media-Wahid-TV-Camera-Pro-1.0.3-FLIP.apk')
a = p.parse_args()
sdk = Path(os.environ['ANDROID_HOME'])
java = Path(os.environ['JAVA_HOME']) / 'bin'
bt = sdk / 'build-tools/36.0.0'
work = root / 'build/recording-device-test'
for name in ('classes', 'dex'):
    (work / name).mkdir(parents=True, exist_ok=True)

def run(*args):
    return subprocess.run([str(x) for x in args], check=True)

def build_harness():
    manifest = work / 'AndroidManifest.xml'
    manifest.write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="tv.mediawahid.camerapro.test">
    <uses-sdk android:minSdkVersion="30" android:targetSdkVersion="36"/>
    <application android:label="Media Wahid TV device tests"/>
    <instrumentation android:name="tv.mediawahid.camerapro.test.RecordingHarness" android:targetPackage="tv.mediawahid.camerapro" android:functionalTest="true"/>
    </manifest>''')
    run(java / 'javac', '-source', '17', '-target', '17', '-cp', sdk / 'platforms/android-36/android.jar',
        '-d', work / 'classes', root / 'tools/device/RecordingHarness.java')
    run(bt / 'd8', '--min-api', '30', '--lib', sdk / 'platforms/android-36/android.jar', '--output', work / 'dex',
        *sorted((work / 'classes').rglob('*.class')))
    run(bt / 'aapt', 'package', '-f', '-M', manifest, '-I', sdk / 'platforms/android-36/android.jar', '-F', work / 'unsigned.apk')
    with zipfile.ZipFile(work / 'unsigned.apk', 'a') as z:
        z.write(work / 'dex/classes.dex', 'classes.dex')
    run(bt / 'zipalign', '-f', '4', work / 'unsigned.apk', work / 'aligned.apk')
    default_cache = Path('/workspace/.media-wahid-tools') if Path('/workspace').is_dir() else Path.home() / '.cache/media-wahid-tools'
    cache = Path(os.environ.get('WAHID_TOOLS', default_cache))
    key = Path(os.environ.get('WAHID_KEYSTORE', cache / 'signing/test.jks'))
    password = Path(os.environ.get('WAHID_KEYSTORE_PASSWORD_FILE', cache / 'signing/password'))
    run(bt / 'apksigner', 'sign', '--ks', key, '--ks-pass', 'file:' + str(password),
        '--ks-key-alias', os.environ.get('WAHID_KEY_ALIAS', 'wahid-test'), '--out', work / 'harness.apk', work / 'aligned.apk')
    return work / 'harness.apk'

harness = a.harness or build_harness()
if a.build_only:
    print('Harness compiled:', harness)
    raise SystemExit(0)
run(a.adb, 'install', '--no-incremental', '-r', a.apk)
for permission in ('CAMERA', 'RECORD_AUDIO'):
    run(a.adb, 'shell', 'pm', 'grant', 'tv.mediawahid.camerapro', 'android.permission.' + permission)
run(a.adb, 'install', '--no-incremental', '-r', harness)
run(a.adb, 'shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
run(a.adb, 'shell', 'wm', 'dismiss-keyguard')
with (root / 'artifacts/recording-events.txt').open('w') as stream:
    capture = subprocess.Popen([a.adb, 'logcat', '-T', '1', '-v', 'threadtime', '-s',
        'MWTV.DeviceTest', 'MWTV.PersistentFlip', 'MWTV.Recording', 'MWTV', 'AndroidRuntime'], stdout=stream, stderr=subprocess.STDOUT)
    try:
        result = subprocess.run([a.adb, 'shell', 'am', 'instrument', '-w',
            'tv.mediawahid.camerapro.test/tv.mediawahid.camerapro.test.RecordingHarness'],
            text=True, capture_output=True, timeout=1200)
    finally:
        capture.terminate()
        capture.wait(timeout=15)
(root / 'artifacts/recording-device.txt').write_text(result.stdout + result.stderr)
log = subprocess.check_output([a.adb, 'logcat', '-d', '-v', 'threadtime'], text=True)
(root / 'artifacts/recording-logcat.txt').write_text(log)
print(result.stdout, flush=True)
assert result.returncode == 0 and 'result=passed' in result.stdout, 'Recording device test failed; inspect recording-device.txt and recording-logcat.txt'
captured = (root / 'artifacts/recording-events.txt').read_text()
events = [line for line in captured.splitlines() if 'MWTV.Recording' in line]
assert sum(' Start ' in line for line in events) == 2, events
assert sum(' Finalize ' in line for line in events) == 2, events
assert all('error=0' in line for line in events if ' Finalize ' in line), events
assert 'FATAL EXCEPTION' not in captured, 'Android process crash observed'
print(json.dumps({'result': 'passed', 'recordings': 2, 'scope': 'Real final-APK service, repeated flip, pause/resume, timer/identity/template locks, rapid taps, stop during flip, idle recovery. Inspect exported MP4 separately.'}))
