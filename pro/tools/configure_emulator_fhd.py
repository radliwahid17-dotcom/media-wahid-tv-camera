#!/usr/bin/env python3
"""Configure FHD software sensors/profiles on a disposable Android 11 emulator.
Synthetic fixture only, never a phone. Does not change the APK's quality rules
and does not validate a physical camera, microphone or real-device performance.
"""
import argparse
import json
from pathlib import Path
import subprocess
import time
import xml.etree.ElementTree as E

p = argparse.ArgumentParser()
p.add_argument('--adb', default='adb')
p.add_argument('--serial', required=True)
a = p.parse_args()
assert a.serial.startswith('emulator-'), 'Only a dedicated emulator is allowed'

def adb(*args):
    return subprocess.check_output([a.adb, '-s', a.serial, *args], text=True, stderr=subprocess.STDOUT, timeout=90)

assert adb('shell', 'getprop', 'ro.kernel.qemu').strip() == '1', 'Not an emulator'
print(adb('root').strip())
adb('wait-for-device')
adb('shell', 'setenforce', '0')
remote = '/vendor/etc/media_profiles_V1_0.xml'
r = E.fromstring(adb('shell', 'cat', remote))
for cam in r.findall('CamcorderProfiles'):
    if cam.get('cameraId') not in ('0', '1'):
        continue
    for old in list(cam.findall('EncoderProfile')):
        if old.get('quality', '').strip() == '1080p': cam.remove(old)
    profile = E.SubElement(cam, 'EncoderProfile', quality='1080p', fileFormat='mp4', duration='60')
    E.SubElement(profile, 'Video', codec='h264', bitRate='16000000', width='1920', height='1080', frameRate='30')
    E.SubElement(profile, 'Audio', codec='aac', bitRate='128000', sampleRate='48000', channels='1')
for cap in r.findall('VideoEncoderCap'):
    if cap.get('name') == 'h264':
        cap.set('maxFrameWidth', '1920')
        cap.set('maxFrameHeight', '1080')
work = Path(__file__).resolve().parents[1] / 'build/emulator-fixture'
work.mkdir(parents=True, exist_ok=True)
local = work / 'media_profiles.xml'
E.ElementTree(r).write(local, encoding='utf-8', xml_declaration=True)
fixture = '/data/local/tmp/wahid-fhd-profiles.xml'
print(adb('push', str(local), fixture).strip())
adb('shell', 'mount', '-o', 'bind', fixture, remote)
sensor_path = '/vendor/etc/config/emu_camera_back.json'
sensor = json.loads(adb('shell', 'cat', sensor_path))
assert sensor['android.sensor.info.pixelArraySize'] in (['1856', '1392'], ['1920', '1440']), 'Unsupported emulator sensor fixture'
for key, values in sensor.items():
    if isinstance(values, list):
        sensor[key] = [{'1856': '1920', '1392': '1440'}.get(value, value) for value in values]
for fmt in ('33', '34', '35'):
    stream = [fmt, '1920', '1080', 'OUTPUT']
    streams = sensor['android.scaler.availableStreamConfigurations']
    if not any(streams[i:i+4] == stream for i in range(0, len(streams), 4)):
        streams += stream
        sensor['android.scaler.availableMinFrameDurations'] += [fmt, '1920', '1080', '33333333']
        sensor['android.scaler.availableStallDurations'] += [fmt, '1920', '1080', '33333333' if fmt == '33' else '0']
sensor_file = work / 'camera-back-fhd.json'
sensor_file.write_text(json.dumps(sensor, indent=2))
adb('push', str(sensor_file), '/data/local/tmp/wahid-back-fhd.json')
adb('shell', 'mount', '-o', 'bind', '/data/local/tmp/wahid-back-fhd.json', sensor_path)
adb('shell', 'setprop', 'qemu.sf.fake_camera', 'both')
for name in ('vendor.camera-provider-2-4', 'vendor.camera-provider-2-6-google', 'cameraserver'):
    adb('shell', 'setprop', 'ctl.restart', name)
# The image disables C2 by default; legacy OMX software AVC aborts on surface
# replacement. Use the image's C2 implementation for the synthetic test only.
adb('shell', 'setprop', 'debug.stagefright.ccodec', '4')
for name in ('media.swcodec', 'vendor.media.omx', 'media'):
    adb('shell', 'setprop', 'ctl.restart', name)
# MediaProfiles may be preloaded by zygote; restart Android userspace to reload it.
adb('shell', 'setprop', 'sys.boot_completed', '0')
adb('shell', 'stop')
adb('shell', 'start')
deadline = time.monotonic() + 300
while time.monotonic() < deadline:
    if adb('shell', 'getprop', 'sys.boot_completed').strip() == '1': break
    time.sleep(2)
else: raise SystemExit('Android userspace restart timed out')
print('Synthetic FHD emulator ready; physical camera/microphone not validated.')
