# Pro 1.0.5: profil gambar foto untuk kamera video

Status: build/lint/18 unit test lokal lulus. Pengujian native revisi ini masih
berjalan. Hasil 1.0.4 bukan bukti revisi ini lulus, dan tidak ada klaim foto/video
identik atau blur Samsung sudah terselesaikan tanpa pemeriksaan file perangkat.

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
  fragment highp. Emulator saat ini sudah memberi 23 bit pada mediump; percobaan
  sebelum patch menunjukkan highp saja tidak meningkatkan detail di emulator.
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

Samsung fisik belum tersedia. Kualitas FHD nyata dibanding foto pada scene yang
sama, fokus bergerak/dekat/jauh, exposure/warna, ucapan/sinkronisasi bibir,
UI setelah STOP, suhu/storage dan rekaman 90 menit tetap wajib diuji. Tidak ada
janji kualitas JPEG dan video lossy identik. Kunci asli tidak tersedia; APK memakai
kunci uji di luar Git dan mungkin perlu instal ulang bila sertifikat berbeda.

## Hasil pengujian revisi ini

Diisi setelah run selesai; build sukses bukan bukti kualitas kamera sudah sama.
