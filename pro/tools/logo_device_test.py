#!/usr/bin/env python3
"""Build a separately signed instrumentation APK, then test the installed final Pro APK."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import zipfile

root = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser()
p.add_argument('--build-only', action='store_true')
p.add_argument('--run-only', action='store_true')
p.add_argument('--adb', default='adb')
a = p.parse_args()
sdk = Path(os.environ['ANDROID_HOME'])
java = Path(os.environ['JAVA_HOME']) / 'bin'
tools = sdk / 'build-tools/36.0.0'
work = root / 'build/logo-test'
test_apk = root / 'artifacts/logo-tests.apk'


def run(*args, **kwargs):
    return subprocess.run([str(arg) for arg in args], check=True, **kwargs)


if not a.run_only:
    for directory in ['stubs', 'classes', 'dex']:
        (work / directory).mkdir(parents=True, exist_ok=True)
    sources = root / 'tools/android-tests'
    library = sdk / 'platforms/android-36/android.jar'
    run(java / 'javac', '-source', '17', '-target', '17', '-cp', library, '-d', work / 'stubs', sources / 'Frame.java')
    run(java / 'javac', '-source', '17', '-target', '17', '-cp', str(library) + os.pathsep + str(work / 'stubs'),
        '-d', work / 'classes', sources / 'LogoInstrumentation.java')
    run(tools / 'd8', '--min-api', '30', '--lib', library, '--classpath', work / 'stubs', '--output', work / 'dex',
        *sorted((work / 'classes').rglob('*.class')))
    unsigned = work / 'unsigned.apk'
    run(tools / 'aapt', 'package', '-f', '-M', sources / 'AndroidManifest.xml', '-I', library, '-F', unsigned)
    with zipfile.ZipFile(unsigned, 'a') as apk:
        apk.write(work / 'dex/classes.dex', 'classes.dex')
    run(tools / 'zipalign', '-f', '4', unsigned, work / 'aligned.apk')
    default_cache = Path('/workspace/.media-wahid-tools') if Path('/workspace').is_dir() else Path.home() / '.cache/media-wahid-tools'
    signing = Path(os.environ.get('WAHID_TOOLS', default_cache)) / 'signing'
    run(tools / 'apksigner', 'sign', '--ks', os.environ.get('WAHID_KEYSTORE', str(signing / 'test.jks')),
        '--ks-key-alias', os.environ.get('WAHID_KEY_ALIAS', 'wahid-test'),
        '--ks-pass', 'file:' + os.environ.get('WAHID_KEYSTORE_PASSWORD_FILE', str(signing / 'password')),
        '--out', test_apk, work / 'aligned.apk')
    run(tools / 'apksigner', 'verify', test_apk)
if not a.build_only:
    run(a.adb, 'shell', 'am', 'force-stop', 'tv.mediawahid.camerapro')
    run(a.adb, 'install', '-r', test_apk)
    result = subprocess.check_output([a.adb, 'shell', 'am', 'instrument', '-w',
        'tv.mediawahid.camerapro.logotest/.LogoInstrumentation'], text=True, stderr=subprocess.STDOUT, timeout=600)
    print(result)
    assert 'LOGO_RENDER_PASS' in result and 'INSTRUMENTATION_CODE: -1' in result, result
    run(a.adb, 'pull', '/sdcard/Android/data/tv.mediawahid.camerapro/files/logo-evidence', root / 'artifacts')
    report = json.loads((root / 'artifacts/logo-evidence/logo-device.json').read_text())
    assert report['result'] == 'passed' and report['frames'] == 32
    (root / 'artifacts/logo-device.json').write_text(json.dumps(report, indent=2) + '\n')
