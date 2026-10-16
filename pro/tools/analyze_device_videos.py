#!/usr/bin/env python3
"""Export and decode the two final harness outputs; report actual stream timing."""
import argparse
import json
from pathlib import Path
import re
import subprocess

p = argparse.ArgumentParser()
p.add_argument('--adb', default='adb')
a = p.parse_args()
root = Path(__file__).resolve().parents[1]
result = (root / 'artifacts/recording-device.txt').read_text()
assert 'result=passed' in result, 'Device harness must pass before media validation'
match = re.search(r'INSTRUMENTATION_RESULT: videos=(\[.*\])', result)
assert match, 'Output URI list absent'
outputs = json.loads(match.group(1))
assert len(outputs) == 2 and len({item['uri'] for item in outputs}) == 2
folder = root / 'artifacts/video-evidence'
folder.mkdir(parents=True, exist_ok=True)
reports = []
for item in outputs:
    query = subprocess.check_output([a.adb, 'shell', 'content', 'query', '--uri', item['uri'], '--projection', '_data'], text=True)
    remote = re.search(r'_data=(.+)', query).group(1).strip()
    local = folder / f"template-{item['template']}.mp4"
    subprocess.run([a.adb, 'pull', remote, str(local)], check=True)
    metadata = json.loads(subprocess.check_output(['ffprobe', '-v', 'error', '-show_streams', '-show_format', '-of', 'json', str(local)], text=True))
    video = next(x for x in metadata['streams'] if x['codec_type'] == 'video')
    audio = next(x for x in metadata['streams'] if x['codec_type'] == 'audio')
    assert {video['width'], video['height']} == {1080, 1920}, video
    assert int(video['nb_frames']) >= 10, 'Insufficient encoded video frames'
    subprocess.run(['ffmpeg', '-v', 'error', '-i', str(local), '-f', 'null', '-'], check=True)
    for position in ('first', 'last'):
        args = ['ffmpeg', '-v', 'error']
        if position == 'last': args += ['-sseof', '-1']
        args += ['-i', str(local), '-frames:v', '1', '-y', str(folder / f"template-{item['template']}-{position}.png")]
        subprocess.run(args, check=True)
    report = {'template': item['template'], 'uri': item['uri'], 'width': video['width'], 'height': video['height'],
              'video_codec': video['codec_name'], 'audio_codec': audio['codec_name'],
              'video_duration': float(video['duration']), 'audio_duration': float(audio['duration']),
              'video_frames': int(video['nb_frames']), 'average_frame_rate': video['avg_frame_rate'], 'decode': 'passed'}
    reports.append(report)
(folder / 'metadata.json').write_text(json.dumps(reports, indent=2) + '\n')
print(json.dumps(reports, indent=2))
assert all(abs(x['video_duration'] - x['audio_duration']) <= 2 for x in reports), 'A/V duration mismatch; inspect metadata.json'
