# Media Wahid TV Camera Pro 1.0.3 FLIP

Melanjutkan patch Pro 1.0.2 pada commit `174e028`, dengan APK Pro 1.0.1 sebagai
basis terverifikasi. Package tetap `tv.mediawahid.camerapro`, versionName `1.0.3`,
versionCode `4`. Aplikasi tidak dibangun ulang dari nol.

Source Kotlin Pro asli tidak tersedia. Modul `flip` mengompilasi delta Java;
APKTool memasangnya ke aplikasi asli sambil mempertahankan resource terkompilasi,
library native, UI, template, dan pipeline foto/video. **Jangan instal APK payload
`flip-debug.apk`; APK yang dapat diinstal berada di `artifacts/`.**

## Perubahan 1.0.3

- Flip menggunakan persistent recording dan mempertahankan Recorder, VideoCapture,
  OverlayEffect, audio, output MediaStore dan timer yang sama.
- Kamera tujuan diperiksa sebelum unbind. Crop stream setelah bind juga wajib
  Full HD; kegagalan memicu upaya kembali ke kamera sebelumnya.
- Brightness/exposure diingat per lensa selama sesi. Nilai EV dikonversi mengikuti
  exposure step/rentang kamera tujuan; kontrol manual asli tetap dipakai.
- Callback lama tidak boleh mengubah kamera yang sudah berganti; observer preview
  dilepas dari view yang benar meskipun preview Activity diganti.
- STOP saat flip diantrekan sampai kamera dan encoder selesai dikonfigurasi.
  Ini memperbaiki assertion CameraX `Recorder.onConfigured` pada state STOPPING.
  Hook kesiapan setelah callback asli tidak mengubah state machine/setting CameraX.
- STARTING/STOPPING, foto, impor logo dan flip ganda ditolak. Pause/resume, lock
  template/mode/orientasi dan pemulihan idle setelah Finalize tetap dipertahankan.
- Logo MEDIA WAHID TV dan Masjid Raya Vila Inti Persada disertakan permanen.
  Sumbernya chunk Base64 artwork yang sudah ada di proyek lama, disalin ke
  `logo-source/`. Checksum dipin; tidak menggunakan JPEG Masjid terurai yang rusak.
  WatermarkStore asli mendahulukan logo impor pengguna dan memakai artwork bawaan
  bila tidak ada impor. Ukuran, opacity, posisi dan dua template tidak diubah.
- FHD SDR, 30 FPS requested, bitrate target 16 Mbps dan JPEG quality 95 tetap
  mengikuti konfigurasi Pro asli. Tidak ditambahkan batas durasi rekaman.

## Build

Prasyarat: JDK 21, Python 3.11+, Android SDK platform 36 dan build-tools 36.0.0.

```bash
./gradlew --no-daemon :flip:lintDebug :flip:testDebugUnitTest assembleFlip
python3 tools/verify_apk.py
```

Gunakan Gradle 8.13 yang sudah terpasang jika distribusi wrapper belum di-cache.
APK referensi, APKTool, distribusi wrapper dan artwork diverifikasi dengan SHA256.
Hasil: `artifacts/Media-Wahid-TV-Camera-Pro-1.0.3-FLIP.apk`, checksum dan provenance.

## Pengujian

```bash
python3 tools/smoke_device.py --adb /path/to/adb
python3 tools/device_state_test.py /path/to/adb
python3 tools/recording_device_test.py --adb /path/to/adb
```

Harness recording hanya untuk emulator/perangkat uji khusus. Ia menginstal test
instrumentation dengan signer yang sama, mengendalikan UI/service APK final,
membuat dua rekaman pada dua template, melakukan flip berulang, memeriksa frame
dan byte terus bertambah setelah flip, pause/resume, tap cepat, STOP saat flip
(recording maupun paused), lalu memeriksa pemulihan idle. MP4, audio dan watermark
hasil tetap perlu dianalisis terpisah. Harness tidak disertakan dalam APK pengguna.
479 entri APK referensi tetap identik, termasuk seluruh resource terkompilasi dan
library native. `classes.dex` mendapat hook kesiapan encoder, `classes3.dex`
mendapat patch service/UI, dan `classes5.dex` memuat delta Java.
Lihat `docs/TESTING-1.0.3.md` untuk hasil aktual dan keterbatasannya.

## Signing dan data lama

Kunci signing Pro 1.0.1 tidak ada di proyek. Build lokal memakai kunci uji yang
sama dengan build lokal 1.0.2 terdahulu; build CI terdahulu dapat memakai kunci
berbeda. Android hanya menerima update dengan package dan sertifikat yang sama.
Jangan menghapus instalasi lama untuk memaksa update: penghapusan dapat menghapus
logo impor dan pengaturan. Untuk update resmi yang mempertahankan data, gunakan
keystore instalasi lama melalui `WAHID_KEYSTORE`,
`WAHID_KEYSTORE_PASSWORD_FILE`, dan `WAHID_KEY_ALIAS`. Key/password tidak
pernah dimasukkan ke source atau artifact.
