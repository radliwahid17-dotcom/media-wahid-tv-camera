"""Minimal, fail-closed bytecode edits against the verified 1.0.1 APK."""
from pathlib import Path
import re

PACKAGE = "Ltv/mediawahid/camerapro/"
HELPER = PACKAGE + "flip/PersistentFlip;"


def once(text, old, new):
    assert text.count(old) == 1, f"Expected exactly one patch site: {old[:100]}"
    return text.replace(old, new, 1)


def patch(decoded: Path):
    root = decoded / "smali_classes3/tv/mediawahid/camerapro"
    service = root / "CameraRecordingService.smali"
    code = service.read_text()
    code = once(code, ".method public final switchCamera()V",
                ".method public final switchCameraOriginal()V")
    code += f"""
.method public final switchCamera()V
    .locals 0
    invoke-static {{p0}}, {HELPER}->switchCamera(Ljava/lang/Object;)V
    return-void
.end method
"""
    site = "    invoke-virtual {v3, v6, v7}, Landroidx/camera/video/PendingRecording;->start("
    code = once(code, site,
                "    invoke-virtual {v3}, Landroidx/camera/video/PendingRecording;->asPersistentRecording()Landroidx/camera/video/PendingRecording;\n\n"
                "    move-result-object v3\n\n" + site)
    # Finalize/start failure and service teardown must release observer callbacks.
    for signature in [".method private final releaseRecordingResources()V", ".method public onDestroy()V"]:
        pattern = re.escape(signature) + r"(\n    \.locals \d+\n)"
        code, count = re.subn(pattern, lambda m: m.group(0) +
                             f"\n    invoke-static {{p0}}, {HELPER}->release(Ljava/lang/Object;)V\n", code)
        assert count == 1, signature
    signature = ".method private final onVideoEvent(Landroidx/camera/video/VideoRecordEvent;)V"
    pattern = re.escape(signature) + r"(\n    \.locals \d+\n)"
    code, count = re.subn(pattern, lambda m: m.group(0) +
                         f"\n    invoke-static/range {{p0 .. p1}}, {PACKAGE}flip/RecordingTrace;->event(Ljava/lang/Object;Ljava/lang/Object;)V\n", code)
    assert count == 1, 'Recording event trace patch'
    service.write_text(code)

    activity = root / "MainActivity.smali"
    code = activity.read_text()
    # Replace only the Flip button's enabled predicate. Other controls retain all locks.
    start = code.index("    :cond_3f\n", code.index(".line 466"))
    end = code.index("    invoke-virtual {v2, v3}, Landroid/widget/Button;->setEnabled(Z)V", start)
    code = code[:start] + f"""    :cond_3f
    invoke-static/range {{p0 .. p1}}, {HELPER}->canFlipActivity(Ljava/lang/Object;Ljava/lang/Object;)Z
    move-result v3

""" + code[end:]
    activity.write_text(code)
    config = decoded / "apktool.yml"
    code = config.read_text()
    code = once(code, "versionCode: 2", "versionCode: 3")
    code = once(code, "versionName: 1.0.1", "versionName: 1.0.2")
    config.write_text(code)
    manifest = decoded / 'AndroidManifest.xml'
    manifest.write_text(once(manifest.read_text(), 'android:debuggable="true"', 'android:debuggable="false"'))
