# Pro 1.0.6 — Restore original image-quality settings

## User-verified baseline

The user reports that **both photo and video from both front/rear cameras** in
`Media-Wahid-TV-Camera-Pro-1.0.1.apk` have the desired natural image quality.
Do not silently change this reference APK. Its pinned SHA256 is:
`62afa607bd606479d18bda64a404ace76ca9d4fdb7c634043b7ba6efbf43c367`.

The source Kotlin for the original APK is unavailable. A bytecode patch cannot
guarantee a pixel-identical reconstruction of the camera pipeline. In particular,
the persistent-flip GPU composition remains an additional video path relative to
the baseline; visual equality on Samsung is **not yet proven**.

## What 1.0.6 changes (relative to 1.0.5)

- **Return to original Recorder encoding preference:** remove the added
  `setTargetVideoEncodingBitRate(24_000_000)` in `MWTVEncoderFactory`.
  The baseline service's 16 Mbps Recorder.Builder setting is preserved.
  The original FHD/SDR/30 FPS target remains unchanged.
- **Return to original video capture/session request construction:** restore the
  original `VideoCapture.Builder.build()` bytecode in
  `tools/patch_baseline.py`. Do not override its default session/capture
  templates to force `TEMPLATE_PREVIEW`. CameraX again selects the baseline
  autofocus/exposure/white-balance behavior.
- **Leave photo capture intact:** the existing baseline photo path is not
  modified, and built-in watermark artwork is retained.
- **Keep flip implementation isolated:** persistent recording, front/back flip
  bridge, original two watermark templates, long recording/storage guards, UI,
  audio and gallery code remain. The extra renderer may still change video
  sampling and frame cadence; seamless flip and camera parity must be tested.
- **Make test fixture mirror original configuration:** the HD-only emulator
  video fixture uses the original 16 Mbps preference and unmodified
  `VideoCapture.Builder.build()`. Capture intent is observed rather than
  requiring the 1.0.5 forced PREVIEW intent. Do not weaken timing thresholds.

## Build / verification gate

Build in `pro/`:

```bash
./gradlew --no-daemon :flip:lintDebug :flip:testDebugUnitTest assembleFlip validationClasspath
python3 tools/verify_apk.py
```

The resulting *test APK* is `artifacts/Media-Wahid-TV-Camera-Pro-1.0.6-ORIGINAL-QUALITY.apk`.
The workflow collects installation, photo, logo, shader detail, and native video
evidence. A successful build **does not** prove that camera output matches 1.0.1.

## Required side-by-side Samsung acceptance test

Use the **same Samsung**, lens, scene, distance, ambient lighting, orientation,
zoom and exposure, with all filters disabled:

1. Photo rear/front: compare resolution, white balance, skin tones, focus,
   text detail, motion and brightness against 1.0.1.
2. Video rear/front: shoot static fine detail and people under bright and
   dim lighting. Compare saved MP4 dimensions, actual FPS, effective bitrate,
   color/contrast, focus transitions, exposure, aliasing and compression.
3. Make a short video with no flip and with repeated flips. Confirm watermark
   placement, audio, color, frame detail, no stretched/cropped/mirrored content
   and uninterrupted recorder/timer. Check the saved files, not only preview.
4. Repeat portrait/landscape, pause/resume, zoom and brightness, then record
   90 minutes if the device has sufficient storage, battery and temperature margin.

**Acceptance:** 1.0.6 may be called image-quality restored only after a real
Samsung A/B comparison shows no material deterioration against 1.0.1 and flip/
audio tests pass. If quality still differs, investigate/bypass the custom
`StableFrameRenderer` without silently sacrificing original capture quality.

## Safety

Keep 1.0.1 untouched as a fallback. 1.0.6 uses a different test signing
certificate from the original APK; it may require uninstall/reinstall.
Back up photos/videos and any settings before uninstalling. Do not call an
unverified APK final or merge until Samsung validation passes.
