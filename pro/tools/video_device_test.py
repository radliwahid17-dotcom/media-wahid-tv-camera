#!/usr/bin/env python3
"""Exercise the installed final service with real emulator HD camera streams, not a new camera app."""
import argparse,json,os,subprocess
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--adb',default='adb');p.add_argument('--quick',action='store_true');a=p.parse_args()
r=Path(__file__).resolve().parents[1]
subprocess.run([a.adb,'shell','am','force-stop','tv.mediawahid.camerapro'],check=True)
subprocess.run([a.adb,'install','-r',str(r/'artifacts/logo-tests.apk')],check=True)
command=[a.adb,'shell','am','instrument','-w','-r','-e','quick',str(a.quick).lower(),'tv.mediawahid.camerapro.logotest/.VideoInstrumentation']
subprocess.run([a.adb,'logcat','-c'],check=True)
try:
    result=subprocess.check_output(command,text=True,stderr=subprocess.STDOUT,timeout=900)
finally:
    log=subprocess.check_output([a.adb,'logcat','-d','-v','threadtime'],text=True,errors='replace')
    (r/'artifacts/video-logcat.txt').write_text(log)
    bridge=[line for line in log.splitlines() if 'MWTV.' in line or 'FATAL EXCEPTION' in line]
    print('\n'.join(bridge[-35:]))
(r/'artifacts/video-instrumentation.log').write_text(result)
print(result)
assert 'VIDEO_CONTINUITY_PASS' in result and 'INSTRUMENTATION_CODE: -1' in result,result
line=next(line for line in result.splitlines() if line.startswith('INSTRUMENTATION_RESULT: report='))
report=json.loads(line.split('=',1)[1]);assert report['result']=='passed' and len(report['sessions'])==3
report['frame_bridge_log']=[line for line in log.splitlines() if 'MWTV.FrameBridge' in line and any(term in line for term in ['New camera frame','Handoff composited','Session frames=','Frame bridge error'])]
(r/'artifacts/video-device.json').write_text(json.dumps(report,indent=2)+'\n')
out=r/'artifacts/video-evidence';out.mkdir(exist_ok=True)
for session in report['sessions']:
    subprocess.run([a.adb,'pull',session['path'],str(out/(session['name']+'.mp4'))],check=True)
subprocess.run([a.adb,'pull','/sdcard/Pictures/MediaWahidVideoValidation',str(out/'playback-frames')],check=True)
# Source artwork comparison on decoded MP4 playback frames (not UI screenshots).
from PIL import Image
source=Path(__file__).with_name('photo_device_test.py').read_text()
helper=source[source.index('def logo_error('):source.index("click('Select photo mode')")]
root=r
exec(helper)
for session in report['sessions']:
    evidence=[]
    for name in session['playback_frames']:
        with Image.open(out/'playback-frames'/name) as image:
            photo=image.convert('RGB')
            media=logo_error(photo,'logo_media_wahid_tv.png',True)
            mosque=logo_error(photo,'logo_masjid.png',False)
        evidence.append({'file':name,'media_error':media,'mosque_error':mosque})
    session['watermark_pixels']=evidence
    (r/'artifacts/video-device.json').write_text(json.dumps(report,indent=2)+'\n')
    assert all(x['media_error']<45 and (x['mosque_error']<45 if session['template']==1 else x['mosque_error']>45) for x in evidence),evidence
print('PASS: encoded video/audio timestamp continuity, playback and actual watermark pixels. Physical Samsung still required.')
