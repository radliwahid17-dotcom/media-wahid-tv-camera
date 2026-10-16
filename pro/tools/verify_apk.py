#!/usr/bin/env python3
"""Structural/artwork regression check; runtime correctness is tested separately."""
import hashlib
from pathlib import Path
import subprocess
import sys
import zipfile
import os
from logo_assets import artwork

root = Path(__file__).resolve().parents[1]
apk = Path(sys.argv[1]) if len(sys.argv) > 1 else root / 'artifacts/Media-Wahid-TV-Camera-Pro-1.0.3-FLIP.apk'
baseline = root / 'Media-Wahid-TV-Camera-Pro-1.0.1.apk'
sdk = Path(os.environ.get('ANDROID_HOME') or os.environ['ANDROID_SDK_ROOT'])
badging = subprocess.check_output([sdk / 'build-tools/36.0.0/aapt', 'dump', 'badging', apk], text=True)
assert "package: name='tv.mediawahid.camerapro' versionCode='4' versionName='1.0.3'" in badging
assert "launchable-activity: name='tv.mediawahid.camerapro.MainActivity'" in badging
assert 'application-debuggable' not in badging, 'Final APK must use release manifest settings'
with zipfile.ZipFile(baseline) as old, zipfile.ZipFile(apk) as new:
    assert new.testzip() is None
    preserved = [n for n in old.namelist() if n not in ('AndroidManifest.xml', 'classes.dex', 'classes3.dex')]
    for name in preserved:
        assert old.read(name) == new.read(name), f'Original artwork/native artifact changed: {name}'
    assert 'classes5.dex' in new.namelist(), 'Flip payload absent'
    dex = b''.join(new.read(n) for n in new.namelist() if n.endswith('.dex'))
    for name in [b'PersistentFlip', b'asPersistentRecording', b'WatermarkPipeline', b'WatermarkGeometry', b'WatermarkStore']:
        assert name in dex, f'Missing component: {name}'
    original_assets = [n for n in old.namelist() if n.startswith('assets/watermarks/')]
    for name, data in artwork().items():
        assert new.read(name) == data, f'Permanent artwork changed: {name}'
    assert set(n for n in new.namelist() if n.startswith('assets/watermarks/')) == set(original_assets) | set(artwork())
    print(f'PASS: identity/version/launcher/DEX; {len(preserved)} original entries byte-identical, including all compiled resources and native libraries')
    print('PASS: two permanent logos recovered byte-for-byte from approved repository source chunks')
print('SHA256:', hashlib.sha256(apk.read_bytes()).hexdigest())
