# Pro 1.0.5: profil gambar foto untuk kamera video

**Status: APK berhasil dibangun, dipasang dan dibuka. Uji fungsional flip/audio/logo
lulus, tetapi dua pemeriksaan timing video masih gagal. Target seamless belum
terpenuhi dan blur Samsung belum dapat dinyatakan terselesaikan.** Tidak ada klaim
foto/video identik tanpa pemeriksaan file perangkat.

- [APK uji terbaru dan checksum/provenance](https://github.com/radliwahid17-dotcom/media-wahid-tv-camera/actions/runs/39058467501/artifacts/12110576092).
- [CI kode `9a74f39`: build SUCCESS, native FAIL timing](https://github.com/radliwahid17-dotcom/media-wahid-tv-camera/actions/runs/39058467501).
- [MP4, frame playback, JSON dan logcat](https://github.com/radliwahid17-dotcom/media-wahid-tv-camera/actions/runs/39058467501/artifacts/12110344176).

Ekstrak artifact dan instal `Media-Wahid-TV-Camera-Pro-1.0.5-QUALITY-FLIP.apk`.
`logo-tests.apk` adalah harness pengujian, bukan aplikasi kamera.

## Perubahan terarah

Keluhan pengguna adalah file video sudah buram/pecah sejak awal rekaman, sebelum
flip. Target resolusi sebelumnya tetap FHD; karena itu masalah belum boleh
disimpulkan sebagai penurunan resolusi hanya dari nama file atau konfigurasi.

- `PhotoVideoProfile`: video menggunakan template request berkelanjutan
  `TEMPLATE_PREVIEW`, sama dengan preview mode foto asli. Pada CameraX 1.6.2 yang
  dipin, `RECORD` memilih continuous-video AF, sedangkan `PREVIEW` memilih
  continuous-picture AF dengan fallback sesuai kemampuan sensor. Default session
  dan capture template disamakan agar StreamSharing juga meneruskannya ke kamera
  nyata. Tidak ada override AF/AE/AWB atau refleksi ke internal CameraX. Touch
  metering, exposure, zoom dan quirk tetap dikelola CameraX.
- Ini menyamakan profil kamera berkelanjutan, bukan memanggil `takePicture()`
  30 kali/detik atau mengubah video menjadi rangkaian JPEG. Foto tetap JPEG95
  dengan capture mode asli; pemrosesan still-capture perangkat bisa berbeda.
- Preferensi bitrate AVC dinaikkan 16 menjadi **24 Mbps** untuk mengurangi
  kompresi detail. CameraX menyesuaikannya ke rentang encoder perangkat. FHD,
  SDR dan target 30 FPS tetap; audio/muxer/persistent Recording tidak diganti.
  Pada target penuh, data video sekitar 180 MB/menit atau 16,2 GB/90 menit,
  ditambah audio/container. Ukuran aktual bergantung encoder dan scene.
- UV shader OES/RGBA memakai `highp` eksplisit bila tersedia; kedua shader memakai
  qualifier varying yang sama. Fallback `mediump` tetap ada untuk GLES2 tanpa
  fragment highp. Perhitungan warna/alpha SDR tetap `mediump`, sesuai source 8-bit;
  seluruh kompositor tidak dipromosikan ke perhitungan 32-bit. Emulator lokal
  memberi 23 bit pada mediump; percobaan sebelum patch menunjukkan highp saja
  tidak meningkatkan detail di emulator lokal. Emulator CI ANGLE mengiklankan
  mediump 10 bit dan highp 23 bit.
  Ini perlindungan presisi perangkat, bukan bukti penyebab blur pengguna.
- Renderer memverifikasi ukuran EGL encoder sama dengan SurfaceRequest. Ia tetap
  mengambil tekstur kamera langsung ke encoder, tanpa perantara berukuran preview
  dan tanpa filter penajaman/brightness buatan. Thread kompositor mendapat
  prioritas display dan swap tanpa vsync bila EGL mengizinkannya, untuk mengurangi
  hambatan scheduling. Jeda pembukaan sensor saat flip tetap mungkin terjadi.

Logo/aset/geometry/template, layout, brightness, mode foto, kontrol dan sesi
rekaman panjang dipertahankan. Tidak ada batas durasi baru, stop/start atau file
segmen untuk flip. Versi `1.0.5`, versionCode `6`, package
`tv.mediawahid.camerapro`. Nama APK:
`Media-Wahid-TV-Camera-Pro-1.0.5-QUALITY-FLIP.apk`.

## Validasi dan batas bukti

File implementasi utama: `flip/.../PhotoVideoProfile.java`,
`flip/.../StableFrameRenderer.java`,
`flip/.../androidx/camera/video/MWTVEncoderFactory.java`, dan
`tools/patch_baseline.py`. Script build/verifikasi, versioning, harness Android,
workflow Actions, dan dokumentasi turut diperbarui. Implementasi persistent
Recording/flip yang sudah ada dipertahankan, bukan diganti dengan segmen.

`QualityInstrumentation` memanggil factory shader **dari APK final terpasang**,
mengompilasi OES dan RGBA, kemudian menggambar pola detail satu pixel pada
1920×1080 dan 1080×1920 di empat rotasi. Hasil dibaca dengan `glReadPixels` dan
dibandingkan dengan sumber. Ini dapat mendeteksi downscale/upscale atau sampling
yang merusak detail; ini bukan pengukuran sensor/OES kamera, codec atau Samsung.

Fixture video memakai HD yang tersedia di emulator dengan preview VGA. Ia memakai
helper profil produksi dan mengamati request Camera2 lewat callback interop,
tanpa mengubah parameter request. Metadata native harus menunjukkan capture intent
PREVIEW sebelum dan sesudah flip. Track audio/video, 26 flip termasuk 20 dalam
tiga menit, pause/resume, satu file per sesi, decode dan pixel logo diuji seperti
sebelumnya. Batas timing tetap video <250 ms, audio <100 ms, span A/V <500 ms.
Tidak ada threshold yang dilonggarkan. Uji timing 1.0.4 masih gagal dan revisi ini
belum otomatis menghapus kegagalan tersebut.
Observer state/display Activity dilepas hanya pada fixture HD untuk menghindari
bind FHD yang tidak didukung emulator. UI FHD/orientasi otomatis/siklus setelah
STOP belum tercakup. Lihat [cakupan arsitektur dan fixture](SEAMLESS-FLIP.md).

Samsung fisik belum tersedia. Kualitas FHD nyata dibanding foto pada scene yang
sama, fokus bergerak/dekat/jauh, exposure/warna, ucapan/sinkronisasi bibir,
UI setelah STOP, suhu/storage dan rekaman 90 menit tetap wajib diuji. Tidak ada
janji kualitas JPEG dan video lossy identik. Kunci asli tidak tersedia; APK memakai
kunci uji di luar Git dan mungkin perlu instal ulang bila sertifikat berbeda.

## Hasil pengujian revisi ini

Lokal: compile, lint, 18 JUnit, APK/signature/zipalign/resource lulus. APK dipasang
dan dibuka tujuh kali tanpa crash; adapter state lulus 37 assertion. Perbandingan
smali juga menunjukkan tujuh body metode asli tetap identik: foto, fokus sentuh,
pause/resume, exposure, dua kontrol zoom dan brightness layar. Setelah shader
disederhanakan, APK dipasang lagi dan 15 assertion detail FHD lulus pada empat
rotasi (mean error kanal 1,55–1,70/255, maksimum 8/255). Ini uji pola sintetis
sebelum kompresi, bukan perbandingan foto/video sensor Samsung.

Run pertama [`39057595664`](https://github.com/radliwahid17-dotcom/media-wahid-tv-camera/actions/runs/39057595664),
kode `0d4d338`: build SUCCESS; native FAIL timing. Seluruh empat rekaman selesai
dengan Finalize error 0; 26 flip, empat MP4, audio, decode/gerak dan pixel logo
lulus. Kedua kamera benar-benar menerima capture intent PREVIEW (1), AF
continuous-picture (4), AE ON (1), AWB AUTO (1), termasuk setelah flip.
Tetapi gap video 500/560/333,334/400 ms melampaui batas <250 ms pada keempat sesi.
Gap paket audio seluruhnya 80 ms dan selisih span A/V 0/40/406,644/46,666 ms lulus.
Stress berisi 20 flip dalam 180 detik dengan satu MP4; video span 179,940 detik,
audio 179,900 detik. Itu tidak menghapus kegagalan kelancaran.

Kegagalan tersebut memicu pembatasan presisi tinggi hanya pada UV, dengan
perhitungan warna SDR tetap mediump (`9a74f39`). Run penuh terakhir
`39058467501` menjalankan `quick=false`, empat rekaman dan 26 flip, termasuk
20 flip dalam tiga menit. Recording/Recorder/VideoCapture tetap sama selama setiap
sesi, timer tidak reset, tap ganda diblokir, dan empat Finalize menghasilkan
error 0. Empat MP4 dan 12 frame playback lulus decode, gerak scene serta pixel logo
sesuai template/orientasi. Request kamera native tetap PREVIEW/AF4/AE1/AWB1 pada
dua lensa. Log encoder mengonfirmasi 1280×720, 24.000.000 bps dan target 30 FPS
untuk fixture HD; ukuran EGL juga 1280×720, tanpa perantara VGA ke encoder.

| Sesi | Flip | Gap PTS video maksimum | Gap paket audio maksimum | Selisih span A/V | Logo/playback |
| --- | ---: | ---: | ---: | ---: | --- |
| Normal, template A | 2 | 266,667 ms — FAIL | 80 ms — PASS | 46,666 ms — PASS | PASS |
| 180 detik, template B | 20 | 266,667 ms — FAIL | 80 ms — PASS | 60 ms — PASS | PASS |
| Pause/flip/resume, A | 2 | 242,200 ms — PASS | 80 ms — PASS | 259,950 ms — PASS | PASS |
| Landscape, A | 2 | 200 ms — PASS | 80 ms — PASS | 46,666 ms — PASS | PASS |

Batas tetap video <250 ms, audio <100 ms dan span A/V <500 ms. Dua kegagalan
video tetap menghasilkan exit gagal dan status `failed-native-timing`; counter
8.020 assertion mencakup dua pemeriksaan yang gagal. Stress menghasilkan satu MP4
dengan span video 179,780 detik dan audio 179,840 detik. Ada 2.584 sample video
(sekitar 14,4 sample/detik), termasuk pengulangan codec; target konfigurasi 30 FPS
bukan bukti 30 frame sensor baru/detik. Readiness flip maksimum 750 ms; frame
terakhir tetap ditahan selama sensor belum tersedia. Swap EGL terukur hingga
174,8 ms dan pekerjaan input 114,6 ms pada sesi landscape renderer software.
Penyebab gap Samsung tidak dapat dipastikan dari hasil emulator ini.

Pemeriksaan CI lainnya lulus: build, lint delta, 18 JUnit, signature/zipalign,
480 entri baseline byte-identik, SHA/CRC kedua logo, tujuh cold launch, 37 assertion
state, 32 kasus Canvas logo, empat foto offline, dan 15 assertion shader/FHD detail.
Mean error kanal pola Full HD 1,56–1,61/255, maksimum 8/255 pada empat rotasi.
Shader referensi lama pada GPU yang sama juga menghasilkan error 1,566/255
(maksimum 8); uji ini **tidak membuktikan shader lama penyebab blur pengguna**.
Biaya sintetis satu draw RGBA FHD sekitar 11,47 ms produksi vs 11,53 ms referensi
(lima sample, bukan benchmark sensor/codec). Profil fokus dan preferensi bitrate
adalah perubahan gambar yang terverifikasi, bukan jaminan kejernihan JPEG.

Lokal, APK final dengan shader revisi juga berhasil dipasang/dibuka ulang tanpa
crash dan 15 assertion detail lulus. SHA256 lokal:
`06a766d8bf13907c85c53f1eb5c5b81d18b0a1f0e08284f9afd0d3634e901797`.
APK CI memakai sertifikat uji per run; gunakan checksum dari artifact CI untuk
file unduhannya, bukan SHA APK lokal. Pengujian Samsung/FHD/ucapan/90 menit di atas
tetap belum dijalankan. Tidak ada klaim semua pengujian berhasil.
