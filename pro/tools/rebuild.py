#!/usr/bin/env python3
"""Rebuild the exact baseline with the compiled flip delta; never modify the reference APK."""
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import urllib.request
import zipfile
from patch_baseline import patch
from logo_assets import artwork

ROOT = Path(__file__).resolve().parents[1]
BASELINE_SHA = '62afa607bd606479d18bda64a404ace76ca9d4fdb7c634043b7ba6efbf43c367'
APKTOOL_SHA = '56d59c524fc764263ba8d345754d8daf55b1887818b15cd3b594f555d249e2db'


def run(*args):
    subprocess.run([str(a) for a in args], check=True, cwd=ROOT)


def sha(path):
    with open(path, 'rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def main():
    baseline = ROOT / 'Media-Wahid-TV-Camera-Pro-1.0.1.apk'
    assert sha(baseline) == BASELINE_SHA, 'Unexpected reference APK; review patch sites first'
    sdk = Path(os.environ.get('ANDROID_HOME') or os.environ['ANDROID_SDK_ROOT'])
    build_tools = sdk / 'build-tools/36.0.0'
    default_cache = Path('/workspace/.media-wahid-tools') if Path('/workspace').is_dir() else Path.home() / '.cache/media-wahid-tools'
    cache = Path(os.environ.get('WAHID_TOOLS', default_cache))
    repository = Path(subprocess.check_output(['git', 'rev-parse', '--show-toplevel'], cwd=ROOT, text=True).strip())
    assert not cache.resolve().is_relative_to(repository.resolve()), 'Tool/signing cache must be outside the repository'
    cache.mkdir(parents=True, exist_ok=True)
    apktool = cache / 'apktool-2.11.1.jar'
    if not apktool.exists():
        urllib.request.urlretrieve('https://github.com/iBotPeaches/Apktool/releases/download/v2.11.1/apktool_2.11.1.jar', apktool)
    assert sha(apktool) == APKTOOL_SHA, 'APKTool checksum mismatch'
    java = Path(os.environ['JAVA_HOME']) / 'bin/java'
    work = ROOT / 'build/reconstructed'
    payload = ROOT / 'build/payload'
    framework = cache / 'framework'
    run(java, '-jar', apktool, 'd', '-f', '-p', framework, '-o', work, baseline)
    run(java, '-jar', apktool, 'd', '-f', '-r', '-p', framework, '-o', payload,
        ROOT / 'flip/build/outputs/apk/debug/flip-debug.apk')
    patch(work)
    # Only our namespace is transplanted, never duplicate newer transitive libraries.
    destination = work / 'smali_classes5/tv/mediawahid/camerapro/flip'
    copied = 0
    for directory in payload.glob('smali*'):
        source = directory / 'tv/mediawahid/camerapro/flip'
        if source.is_dir():
            shutil.copytree(source, destination, dirs_exist_ok=True)
            copied += len(list(source.rglob('*.smali')))
    assert copied >= 4, 'Compiled flip classes missing'
    output = ROOT / 'artifacts'
    output.mkdir(exist_ok=True)
    rebuilt = ROOT / 'build/rebuilt.apk'
    unsigned = ROOT / 'build/unsigned.apk'
    aligned = ROOT / 'build/aligned.apk'
    final = output / 'Media-Wahid-TV-Camera-Pro-1.0.3-FLIP.apk'
    run(java, '-jar', apktool, 'b', '-p', framework, '-o', rebuilt, work)
    # Keep the original compiled resources and every unrelated DEX/native entry byte-for-byte.
    # APKTool normalizes resource qualifiers/nine-patches when rebuilding; those changes are unnecessary.
    replacements = {'AndroidManifest.xml', 'classes.dex', 'classes3.dex'}
    with zipfile.ZipFile(baseline) as old, zipfile.ZipFile(rebuilt) as delta, \
            zipfile.ZipFile(unsigned, 'w') as result:
        for entry in old.infolist():
            result.writestr(entry, delta.read(entry.filename) if entry.filename in replacements else old.read(entry.filename))
        result.writestr('classes5.dex', delta.read('classes5.dex'), compress_type=zipfile.ZIP_STORED)
        # Original WatermarkStore already supports bundled JPEGs as a fallback;
        # gallery-imported logos and all saved settings retain their precedence.
        for name, data in artwork().items():
            assert name not in old.namelist(), 'Never replace a baseline asset'
            result.writestr(name, data)
    run(build_tools / 'zipalign', '-P', '16', '-f', '4', unsigned, aligned)
    # Local/CI test signing identity stays outside Git; original private key is unavailable.
    signing = cache / 'signing'
    signing.mkdir(mode=0o700, exist_ok=True)
    key = Path(os.environ.get('WAHID_KEYSTORE', signing / 'test.jks'))
    password_file = Path(os.environ.get('WAHID_KEYSTORE_PASSWORD_FILE', signing / 'password'))
    alias = os.environ.get('WAHID_KEY_ALIAS', 'wahid-test')
    if not key.exists():
        assert 'WAHID_KEYSTORE' not in os.environ, 'Configured signing key does not exist'
        password_file.write_text(secrets.token_urlsafe(32))
        password_file.chmod(0o600)
        run(Path(os.environ['JAVA_HOME']) / 'bin/keytool', '-genkeypair', '-noprompt',
            '-keystore', key, '-storepass:file', password_file, '-keypass:file', password_file,
            '-alias', alias, '-keyalg', 'RSA', '-keysize', '3072', '-validity', '10000',
            '-dname', 'CN=Media Wahid TV test build')
        key.chmod(0o600)
    run(build_tools / 'apksigner', 'sign', '--ks', key, '--ks-key-alias', alias,
        '--ks-pass', 'file:' + str(password_file),
        '--out', final, aligned)
    run(build_tools / 'apksigner', 'verify', '--verbose', '--print-certs', final)
    run(build_tools / 'zipalign', '-c', '-P', '16', '4', final)
    (output / (final.name + '.sha256')).write_text(sha(final) + '  ' + final.name + '\n')
    (output / 'provenance.json').write_text(json.dumps({
        'baseline_sha256': BASELINE_SHA, 'apk_sha256': sha(final),
        'package': 'tv.mediawahid.camerapro', 'version_name': '1.0.3', 'version_code': 4,
        'method': 'APKTool baseline reconstruction + compiled Java delta',
        'signing': 'Test identity; not the original 1.0.1 key',
        'bundled_artwork': {name: hashlib.sha256(data).hexdigest() for name, data in artwork().items()},
    }, indent=2) + '\n')
    print('APK:', final)


if __name__ == '__main__':
    main()
