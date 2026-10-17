#!/usr/bin/env python3
"""Exercise the installed final service with real emulator HD camera streams, not a new camera app."""
import argparse,json,re,subprocess
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
diagnostics='\nBridge diagnostics:\n'+'\n'.join([line for line in bridge if not re.search(r':\s+at ',line)][-22:])
native_passed='VIDEO_CONTINUITY_PASS' in result and 'INSTRUMENTATION_CODE: -1' in result
line=next((line for line in result.splitlines() if line.startswith('INSTRUMENTATION_RESULT: report=')),None)
assert line is not None,result+diagnostics
report=json.loads(line.split('=',1)[1])
report['native_result']=report['result']
report['frame_bridge_log']=[line for line in log.splitlines() if 'MWTV.FrameBridge' in line and any(term in line for term in ['New camera frame','Handoff composited','Session frames=','Frame bridge error','Muxer timestamps adjusted='])]
report['result']='watermark-validation-pending'
(r/'artifacts/video-device.json').write_text(json.dumps(report,indent=2)+'\n')
out=r/'artifacts/video-evidence';out.mkdir(exist_ok=True)
for session in report['sessions']:
    subprocess.run([a.adb,'pull',session['path'],str(out/(session['name']+'.mp4'))],check=True)
subprocess.run([a.adb,'pull','/sdcard/Pictures/MediaWahidVideoValidation',str(out/'playback-frames')],check=True)
# Source artwork comparison on decoded MP4 playback frames (not UI screenshots).
from PIL import Image,ImageChops,ImageStat
source=Path(__file__).with_name('photo_device_test.py').read_text()
helper=source[source.index('def logo_error('):source.index("click('Select photo mode')")]
root=r
exec(helper)
for session in report['sessions']:
    evidence=[]
    previous=None;motion=[]
    for name in session['playback_frames']:
        with Image.open(out/'playback-frames'/name) as image:
            photo=image.convert('RGB')
            media=logo_error(photo,'logo_media_wahid_tv.png',True)
            mosque=logo_error(photo,'logo_masjid.png',False)
            placement={}
            if media>=45 or (mosque>=45 if session['template']==1 else mosque<=45):
                # Diagnose orientation without changing any saved evidence image.
                for transform in [Image.Transpose.FLIP_TOP_BOTTOM,Image.Transpose.FLIP_LEFT_RIGHT,
                                  Image.Transpose.ROTATE_90,Image.Transpose.ROTATE_180,Image.Transpose.ROTATE_270]:
                    candidate=photo.transpose(transform)
                    placement[transform.name]=[logo_error(candidate,'logo_media_wahid_tv.png',True),logo_error(candidate,'logo_masjid.png',False)]
            if previous is not None:
                difference=ImageChops.difference(previous,photo).crop((0,photo.height//4,photo.width,photo.height)).resize((64,64))
                motion.append(round(sum(ImageStat.Stat(difference).mean)/3,3))
            previous=photo.copy()
        evidence.append({'file':name,'media_error':media,'mosque_error':mosque,'placement_diagnostics':placement})
    session['watermark_pixels']=evidence
    session['animated_emulator_playback_difference']=motion
    (r/'artifacts/video-device.json').write_text(json.dumps(report,indent=2)+'\n')
    if not all(x['media_error']<45 and (x['mosque_error']<45 if session['template']==1 else x['mosque_error']>45) for x in evidence):
        report['result']='failed-watermark-pixels'
        (r/'artifacts/video-device.json').write_text(json.dumps(report,indent=2)+'\n')
        raise AssertionError(evidence)
    if not motion or max(motion)<=1:
        report['result']='failed-frozen-emulator-playback'
        (r/'artifacts/video-device.json').write_text(json.dumps(report,indent=2)+'\n')
        raise AssertionError('Animated emulator camera content frozen: '+str(motion))
report['result']='passed' if native_passed else ('failed-native-functional' if report['native_result']=='failed-functional' else 'failed-native-timing')
(r/'artifacts/video-device.json').write_text(json.dumps(report,indent=2)+'\n')
assert native_passed and len(report['sessions'])==4,report.get('failure',json.dumps(report.get('timing_failures',[])))+diagnostics
print('PASS: encoded video/audio timestamp continuity, playback and actual watermark pixels. Physical Samsung still required.')
