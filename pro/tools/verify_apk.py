#!/usr/bin/env python3
"""Structural/artwork regression check; runtime correctness is tested separately."""
import hashlib
from pathlib import Path
import subprocess
import sys
import zipfile
import os
import json
import struct
import zlib

root = Path(__file__).resolve().parents[1]
apk = Path(sys.argv[1]) if len(sys.argv) > 1 else root / 'artifacts/Media-Wahid-TV-Camera-Pro-1.0.5-QUALITY-FLIP.apk'
baseline = root / 'Media-Wahid-TV-Camera-Pro-1.0.1.apk'
sdk = Path(os.environ.get('ANDROID_HOME') or os.environ['ANDROID_SDK_ROOT'])
badging = subprocess.check_output([sdk / 'build-tools/36.0.0/aapt', 'dump', 'badging', apk], text=True)
assert "package: name='tv.mediawahid.camerapro' versionCode='6' versionName='1.0.5'" in badging
assert "launchable-activity: name='tv.mediawahid.camerapro.MainActivity'" in badging
assert 'application-debuggable' not in badging, 'Final APK must use release manifest settings'
with zipfile.ZipFile(baseline) as old, zipfile.ZipFile(apk) as new:
    assert new.testzip() is None
    preserved = [n for n in old.namelist() if n not in ('AndroidManifest.xml', 'classes3.dex')]
    for name in preserved:
        assert old.read(name) == new.read(name), f'Original artwork/native artifact changed: {name}'
    assert 'classes5.dex' in new.namelist(), 'Flip payload absent'
    dex = b''.join(new.read(n) for n in new.namelist() if n.endswith('.dex'))
    for name in [b'PersistentFlip', b'asPersistentRecording', b'WatermarkPipeline', b'WatermarkGeometry', b'WatermarkStore', b'StableVideoOutput', b'StableFrameRenderer', b'VideoWatermarks', b'PhotoVideoProfile', b'MWTVEncoderFactory', b'MWTVRepeatingVideoConfig']:
        assert name in dex, f'Missing component: {name}'
    logos = json.loads((root / 'assets/artwork.json').read_text())['logos']
    assert sorted('assets/watermarks/' + n for n in logos) == sorted(n for n in new.namelist() if n.startswith('assets/watermarks/'))
    for name, info in logos.items():
        data = new.read('assets/watermarks/' + name)
        assert hashlib.sha256(data).hexdigest() == info['png_sha256']
        assert data[:8] == b'\x89PNG\r\n\x1a\n'
        assert struct.unpack('>II', data[16:24]) == (303, 174)
        offset = 8
        while offset < len(data):
            length = struct.unpack('>I', data[offset:offset+4])[0]
            chunk = data[offset+4:offset+8+length]
            assert zlib.crc32(chunk) == struct.unpack('>I', data[offset+8+length:offset+12+length])[0]
            offset += 12 + length
        assert offset == len(data)
    for retired in [b'REPLACE LOGO', b'CHOOSE FROM GALLERY', b'Logo TV dari Galeri', b'Logo masjid dari Galeri', b'Temporary logos']:
        assert retired not in dex, f'Retired placeholder/import UI remains: {retired}'
    print(f'PASS: identity/version/launcher/DEX; {len(preserved)} original entries byte-identical, including all compiled resources and native libraries')
    print('PASS: both permanent PNG logos, exact SHA256/dimensions/chunk CRCs; no placeholder badges or gallery-import controls')
print('SHA256:', hashlib.sha256(apk.read_bytes()).hexdigest())
