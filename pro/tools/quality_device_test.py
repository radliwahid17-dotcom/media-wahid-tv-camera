#!/usr/bin/env python3
"""Check fine detail with the installed APK's real GL shader, without a camera-profile fallback."""
import argparse
import json
from pathlib import Path
import subprocess

p=argparse.ArgumentParser();p.add_argument('--adb',default='adb');args=p.parse_args()
root=Path(__file__).resolve().parents[1]
subprocess.run([args.adb,'shell','am','force-stop','tv.mediawahid.camerapro'],check=True)
subprocess.run([args.adb,'install','-r',str(root/'artifacts/logo-tests.apk')],check=True)
run=subprocess.run([args.adb,'shell','am','instrument','-w','-r','tv.mediawahid.camerapro.logotest/.QualityInstrumentation'],text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=300)
(root/'artifacts/quality-instrumentation.log').write_text(run.stdout)
print(run.stdout)
assert run.returncode==0 and 'VIDEO_DETAIL_PASS' in run.stdout and 'INSTRUMENTATION_CODE: -1' in run.stdout,run.stdout
line=next(line for line in run.stdout.splitlines() if line.startswith('INSTRUMENTATION_RESULT: report='))
report=json.loads(line.split('=',1)[1]);assert report['result']=='passed' and len(report['cases'])==4
(root/'artifacts/video-detail.json').write_text(json.dumps(report,indent=2)+'\n')
