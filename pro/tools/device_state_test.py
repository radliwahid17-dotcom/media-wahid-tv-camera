#!/usr/bin/env python3
"""Exercise the reflection adapter against the actual rebuilt APK's state classes on Android."""
import json
import os
from pathlib import Path
import subprocess
import sys

root = Path(__file__).resolve().parents[1]
sdk = Path(os.environ['ANDROID_HOME'])
java = Path(os.environ['JAVA_HOME']) / 'bin'
adb = sys.argv[1] if len(sys.argv) > 1 else 'adb'
work = root / 'build/device-state'
(work / 'stub/tv/mediawahid/camerapro').mkdir(parents=True, exist_ok=True)
(work / 'classes').mkdir(exist_ok=True)
(work / 'dex').mkdir(exist_ok=True)
stub = work / 'stub/tv/mediawahid/camerapro/CameraUiState.java'
stub.write_text('package tv.mediawahid.camerapro; public class CameraUiState {}\n')
source = work / 'StateHarness.java'
source.write_text(r'''
package tv.mediawahid.camerapro.flip;
import java.lang.reflect.*;
import java.util.*;
import tv.mediawahid.camerapro.CameraUiState;
public class StateHarness {
  static int checks;
  static Object call(Object x, String method, Object... args) throws Exception {
    for (Method m : x.getClass().getDeclaredMethods())
      if (m.getName().equals(method) && m.getParameterCount()==args.length) {
        m.setAccessible(true); return m.invoke(x,args);
      }
    throw new NoSuchMethodException(method);
  }
  static Object access(String method, Object... args) throws Exception {
    for (Method m : Class.forName("tv.mediawahid.camerapro.flip.OriginalAccess").getDeclaredMethods())
      if (m.getName().equals(method) && m.getParameterCount()==args.length) {
        m.setAccessible(true); return m.invoke(null,args);
      }
    throw new NoSuchMethodException(method);
  }
  static void check(boolean condition) { checks++; if(!condition) throw new AssertionError("check "+checks); }
  public static class Service {
    private CameraUiState state;
    private CameraUiState getSnapshot() { return state; }
    private void publish(CameraUiState value) { state=value; }
  }
  public static void main(String[] ignored) throws Exception {
    Service s=new Service();
    s.state=(CameraUiState)Class.forName("tv.mediawahid.camerapro.CameraUiState").getConstructor().newInstance();
    Class<?> phase=Class.forName("tv.mediawahid.camerapro.RecordingPhase");
    Map<Integer,Object> values=new HashMap<>();
    values.put(1,true); values.put(3,phase.getField("RECORDING").get(null)); values.put(5,true);
    values.put(16,123456789000L); values.put(17,9876543L); values.put(25,true);
    access("publish",s,values);
    check((Boolean)call(s.state,"getConfigurationLocked"));
    Method can=Class.forName("tv.mediawahid.camerapro.flip.PersistentFlip").getMethod("canFlip",Object.class,boolean.class);
    check((Boolean)can.invoke(null,s.state,true));
    Object[] before=new Object[30]; for(int i=0;i<30;i++) before[i]=call(s.state,"component"+(i+1));
    access("publish",s,Map.of(4,true,5,false,22,"Switching camera…"));
    for(int i=1;i<=30;i++) if(i!=4&&i!=5&&i!=22) check(Objects.equals(before[i-1],call(s.state,"component"+i)));
    check(!(Boolean)can.invoke(null,s.state,true));
    check((Boolean)call(s.state,"getFrontCamera"));
    check((Long)call(s.state,"getElapsedNanos")==123456789000L);
    check((Long)call(s.state,"getBytesRecorded")==9876543L);
    check((Boolean)call(s.state,"getAudioWarning"));
    for(String name:new String[]{"PAUSED","STARTING","STOPPING"}) {
      access("publish",s,Map.of(3,phase.getField(name).get(null),5,true));
      check((Boolean)can.invoke(null,s.state,true)==name.equals("PAUSED"));
    }
    System.out.println("STATE_BRIDGE_PASS "+checks);
  }
}
''')


def run(*args, **kwargs):
    return subprocess.run([str(x) for x in args], check=True, **kwargs)


run(java / 'javac', '-d', work / 'stub', stub)
run(java / 'javac', '-source', '17', '-target', '17', '-cp', work / 'stub', '-d', work / 'classes', source)
run(sdk / 'build-tools/36.0.0/d8', '--min-api', '30', '--output', work / 'dex',
    '--lib', sdk / 'platforms/android-36/android.jar', '--classpath', work / 'stub',
    *sorted((work / 'classes').rglob('*.class')))
run(adb, 'push', root / 'artifacts/Media-Wahid-TV-Camera-Pro-1.0.2-FLIP.apk', '/data/local/tmp/wahid-state.apk')
run(adb, 'push', work / 'dex/classes.dex', '/data/local/tmp/wahid-state.dex')
result = subprocess.check_output([adb, 'shell', 'dalvikvm', '-cp',
    '/data/local/tmp/wahid-state.apk:/data/local/tmp/wahid-state.dex',
    'tv.mediawahid.camerapro.flip.StateHarness'], text=True, stderr=subprocess.STDOUT)
print(result)
assert 'STATE_BRIDGE_PASS 37' in result, result
(root / 'artifacts/device-state.json').write_text(json.dumps({
    'result': 'passed', 'assertions': 37,
    'scope': 'Actual Android CameraUiState copy adapter, recording locks, flip predicate, timer/bytes/audio preservation; no camera hardware exercised',
}, indent=2) + '\n')
