#!/usr/bin/env python3
"""Capture four photo samples via the original UI; artwork needs separate visual inspection."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

p = argparse.ArgumentParser()
p.add_argument('--adb', default='adb')
a = p.parse_args()
root = Path(__file__).resolve().parents[1]
output = root / 'artifacts/photo-evidence'
output.mkdir(parents=True, exist_ok=True)


def adb(*args):
    return subprocess.check_output([a.adb, *args], text=True, stderr=subprocess.STDOUT, timeout=90)


def ui():
    adb('shell', 'uiautomator', 'dump', '/sdcard/wahid-photo-test.xml')
    return ET.fromstring(adb('shell', 'cat', '/sdcard/wahid-photo-test.xml'))


def click(description):
    tree = ui()
    node = next(n for n in tree.iter('node') if n.get('content-desc') == description)
    assert node.get('enabled') == 'true', description + ' disabled'
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
    adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))


def ready(front):
    for _ in range(12):
        tree = ui()
        text = ' '.join(n.get('text', '') for n in tree.iter('node'))
        if 'Ready · Watermarked photo' in text and ('FRONT' if front else 'REAR') in text:
            return
        time.sleep(1)
    raise AssertionError('Photo camera not ready: ' + text)


def files():
    return {line for line in adb('shell', 'find', '/sdcard/Pictures', '-type', 'f').splitlines()
            if line.lower().endswith(('.jpg', '.jpeg'))}


click('Select photo mode')
ready(False)
outcomes = []
for front, template in [(False, 1), (False, 2), (True, 2), (True, 1)]:
    if front and len(outcomes) == 2:
        click('Switch front and rear cameras')
        ready(True)
    click('Template 1: Media Wahid TV and mosque logos' if template == 1 else 'Template 2: Media Wahid TV logo only')
    ready(front)
    old = files()
    click('Take photo with watermarks')
    saved = set()
    for _ in range(20):
        saved = files() - old
        if saved: break
        time.sleep(1)
    assert len(saved) == 1, 'Expected one saved JPEG: ' + repr(saved)
    remote = saved.pop()
    local = output / (('front' if front else 'rear') + '-template-' + str(template) + '.jpg')
    adb('pull', remote, str(local))
    assert local.stat().st_size > 1000
    metadata = json.loads(subprocess.check_output(['ffprobe', '-v', 'error', '-show_streams', '-of', 'json', local], text=True))
    stream = metadata['streams'][0]
    assert {stream['width'], stream['height']} == {1080, 1920}, stream
    outcomes.append({'front': front, 'template': template, 'gallery_file': remote,
                     'width': stream['width'], 'height': stream['height'], 'capture': 'passed'})
    print(json.dumps(outcomes[-1]), flush=True)
    ready(front)
(root / 'artifacts/photo-device.json').write_text(json.dumps({
    'outcomes': outcomes,
    'limitation': 'Original gallery artwork is absent; photos use baseline replacement badges. Visual inspection recorded separately.',
}, indent=2) + '\n')
