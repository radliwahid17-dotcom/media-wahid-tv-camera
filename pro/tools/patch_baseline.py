"""Minimal, fail-closed bytecode edits against the verified 1.0.1 APK."""
from pathlib import Path
import re

PACKAGE = "Ltv/mediawahid/camerapro/"
HELPER = PACKAGE + "flip/PersistentFlip;"


def once(text, old, new):
    assert text.count(old) == 1, f"Expected exactly one patch site: {old[:100]}"
    return text.replace(old, new, 1)


def method(text, signature, body):
    pattern = re.escape(signature) + r"\n.*?^\.end method"
    text, count = re.subn(pattern, lambda _: signature + "\n" + body + "\n.end method",
                          text, flags=re.MULTILINE | re.DOTALL)
    assert count == 1, signature
    return text


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
    # Keep the original settings controls, but remove both gallery-import buttons.
    start = code.index('    new-instance v1, Ltv/mediawahid/camerapro/MainActivity$$ExternalSyntheticLambda30;',
                       code.index('.method private final showSettings()V'))
    end = code.index('    .line 644\n', start)
    code = code[:start] + code[end:]
    code = once(code, 'Pilih PNG atau JPEG dari Galeri. Logo disimpan di aplikasi dan dipakai untuk rekaman berikutnya. Latar putih JPEG tetap dipertahankan.',
                'Logo Masjid dan Media Wahid TV sudah menjadi aset bawaan aplikasi dan tersedia offline. Latar putih artwork asli dipertahankan.')
    code = once(code, r'Temporary logos \u00b7 choose your logos from Gallery in Settings',
                'Logo bawaan tidak tersedia')
    # Retired activity-result callbacks cannot import a stale gallery selection.
    for signature in [
        '.method private final pickLogo(Ltv/mediawahid/camerapro/LogoSlot;)V',
        '.method private final importLogo(Ltv/mediawahid/camerapro/LogoSlot;Landroid/net/Uri;)V',
    ]:
        code = method(code, signature, '    .locals 0\n    return-void')
    activity.write_text(code)

    store = root / 'WatermarkStore.smali'
    code = store.read_text()
    code = once(code, 'const-string v0, "media_wahid_tv"', 'const-string v0, "logo_media_wahid_tv"')
    code = once(code, 'const-string v0, "mosque"', 'const-string v0, "logo_masjid"')
    prefix = 'Ltv/mediawahid/camerapro/WatermarkStore;'
    signature = '.method public final declared-synchronized loadLogo(Ltv/mediawahid/camerapro/LogoSlot;)Landroid/graphics/Bitmap;'
    code = method(code, signature, f'''    .locals 2
    invoke-direct {{p0, p1}}, {prefix}->bundledLogoPath(Ltv/mediawahid/camerapro/LogoSlot;)Ljava/lang/String;
    move-result-object v0
    if-nez v0, :available
    new-instance v0, Ljava/lang/IllegalStateException;
    const-string v1, "Required bundled watermark asset missing"
    invoke-direct {{v0, v1}}, Ljava/lang/IllegalStateException;-><init>(Ljava/lang/String;)V
    throw v0
    :available
    invoke-direct {{p0, v0}}, {prefix}->decodeBundledLogo(Ljava/lang/String;)Landroid/graphics/Bitmap;
    move-result-object v0
    return-object v0''')
    # No mutable cache is read: installed assets always win over old manual imports.
    code = code.replace(signature, signature.replace('declared-synchronized ', ''))
    signature = '.method public final declared-synchronized hasLogo(Ltv/mediawahid/camerapro/LogoSlot;)Z'
    code = method(code, signature, f'''    .locals 1
    invoke-direct {{p0, p1}}, {prefix}->bundledLogoPath(Ltv/mediawahid/camerapro/LogoSlot;)Ljava/lang/String;
    move-result-object v0
    if-eqz v0, :missing
    const/4 v0, 0x1
    return v0
    :missing
    const/4 v0, 0x0
    return v0''')
    code = code.replace(signature, signature.replace('declared-synchronized ', ''))
    signature = '.method public final declared-synchronized importLogo(Ltv/mediawahid/camerapro/LogoSlot;Landroid/net/Uri;)V'
    code = method(code, signature, '''    .locals 2
    new-instance v0, Ljava/lang/UnsupportedOperationException;
    const-string v1, "Watermark artwork is bundled permanently"
    invoke-direct {v0, v1}, Ljava/lang/UnsupportedOperationException;-><init>(Ljava/lang/String;)V
    throw v0''')
    code = code.replace(signature, signature.replace('declared-synchronized ', ''))
    code, count = re.subn(r'^\.method private final replacementBadge\(.*?^\.end method\n', '', code,
                          flags=re.MULTILINE | re.DOTALL)
    assert count == 1, 'Remove generated placeholder badge'
    store.write_text(code)
    config = decoded / "apktool.yml"
    code = config.read_text()
    code = once(code, "versionCode: 2", "versionCode: 4")
    code = once(code, "versionName: 1.0.1", "versionName: 1.0.3")
    config.write_text(code)
    manifest = decoded / 'AndroidManifest.xml'
    manifest.write_text(once(manifest.read_text(), 'android:debuggable="true"', 'android:debuggable="false"'))
