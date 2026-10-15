#!/usr/bin/env python3
"""Capture four real camera photos and check the bundled artwork in the saved JPEG pixels."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from PIL import Image

p = argparse.ArgumentParser()
p.add_argument('--adb', default='adb')
p.add_argument('--allow-resolution-fallback', action='store_true',
               help='Test logo embedding at the emulator camera resolution; still report failed FHD assertions')
a = p.parse_args()
root = Path(__file__).resolve().parents[1]
output = root / 'artifacts/photo-evidence'
output.mkdir(parents=True, exist_ok=True)
last_tree = None


def adb(*args):
    return subprocess.check_output([a.adb, *args], text=True, stderr=subprocess.STDOUT, timeout=90)


def ui():
    global last_tree
    adb('shell', 'uiautomator', 'dump', '/sdcard/wahid-photo-test.xml')
    last_tree = ET.fromstring(adb('shell', 'cat', '/sdcard/wahid-photo-test.xml'))
    return last_tree


def click(description):
    tree = last_tree if last_tree is not None else ui()
    node = next((n for n in tree.iter('node') if n.get('content-desc') == description), None)
    assert node is not None, description + ' missing; UI: ' + repr([(n.get('text'), n.get('content-desc')) for n in tree.iter('node') if n.get('text') or n.get('content-desc')])
    assert node.get('enabled') == 'true', description + ' disabled'
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
    adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))


def ready(front):
    for _ in range(12):
        tree = ui()
        text = ' '.join(n.get('text', '') for n in tree.iter('node'))
        shutter = next((n for n in tree.iter('node') if n.get('content-desc') == 'Take photo with watermarks'), None)
        if 'PHOTO' in text and ('FRONT' if front else 'REAR') in text and shutter is not None and shutter.get('enabled') == 'true':
            return
        time.sleep(1)
    raise AssertionError('Photo camera not ready: ' + text)


def files():
    return {line for line in adb('shell', 'find', '/sdcard/Pictures', '-type', 'f').splitlines()
            if line.lower().endswith(('.jpg', '.jpeg')) and not Path(line).name.startswith('.')}


def logo_error(photo, name, right):
    """Compare source artwork samples with their expected positions; never alter evidence images."""
    source = Image.open(root / 'assets/watermarks' / name).convert('RGB')
    width, height = photo.size
    logo_width = width * .25
    logo_height = logo_width * source.height / source.width
    left = width * .97 - logo_width if right else width * .03
    top = height * .03
    errors = []
    for y in range(2, source.height - 2, 3):
        for x in range(2, source.width - 2, 3):
            expected = source.getpixel((x, y))
            if min(expected) > 200:  # Ignore white margins: test the recognizable coloured artwork.
                continue
            actual = photo.getpixel((int(left + (x + .5) * logo_width / source.width),
                                     int(top + (y + .5) * logo_height / source.height)))
            errors.append(sum(abs(a-b) for a,b in zip(actual, expected)) / 3)
    assert errors
    return round(sum(errors) / len(errors), 3)


click('Select photo mode')
ready(False)
outcomes = []
for front, template in [(False, 1), (False, 2), (True, 2), (True, 1)]:
    if front and len(outcomes) == 2:
        click('Switch front and rear cameras')
        ready(True)
    click('Template 1: Media Wahid TV and mosque logos' if template == 1 else 'Template 2: Media Wahid TV logo only')
    ready(front)
    stage = ('front' if front else 'rear') + '-template-' + str(template)
    with (output / (stage + '-preview.png')).open('wb') as screenshot:
        subprocess.run([a.adb, 'exec-out', 'screencap', '-p'], stdout=screenshot, check=True, timeout=90)
    old = files()
    click('Take photo with watermarks')
    saved = set()
    for _ in range(20):
        saved = files() - old
        if saved: break
        time.sleep(1)
    assert len(saved) == 1, 'Expected one saved JPEG: ' + repr(saved)
    remote = saved.pop()
    local = output / (stage + '.jpg')
    adb('pull', remote, str(local))
    assert local.stat().st_size > 1000
    with Image.open(local) as image:
        assert image.format == 'JPEG', 'Expected a real JPEG from ImageCapture'
        width, height = image.size
        photo = image.convert('RGB')
        media_error = logo_error(photo, 'logo_media_wahid_tv.png', True)
        mosque_error = logo_error(photo, 'logo_masjid.png', False)
    full_hd = {width, height} == {1080, 1920}
    outcomes.append({'front': front, 'template': template, 'gallery_file': remote,
                     'width': width, 'height': height, 'capture': 'passed',
                     'media_logo_pixel_error': media_error, 'mosque_logo_pixel_error': mosque_error,
                     'logos': 'passed' if media_error < 45 and ((mosque_error < 45) if template == 1 else (mosque_error > 45)) else 'failed',
                     'full_hd_assertion': 'passed' if full_hd else 'failed'})
    print(json.dumps(outcomes[-1]), flush=True)
    ready(front)
(root / 'artifacts/photo-device.json').write_text(json.dumps({
    'outcomes': outcomes,
    'limitation': 'Real emulator camera JPEGs; FHD fallback is reported separately. No video/audio or Samsung hardware exercised.',
}, indent=2) + '\n')
assert all(x['logos'] == 'passed' for x in outcomes), 'Saved JPEG artwork mismatch; see photo-device.json'
if not a.allow_resolution_fallback:
    assert all(x['full_hd_assertion'] == 'passed' for x in outcomes), 'Photos saved, but Full HD resolution assertion failed; see photo-device.json'
