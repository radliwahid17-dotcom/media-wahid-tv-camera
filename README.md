# MEDIA WAHID TV Camera v5.0.0 — FINAL

Aplikasi kamera Android khusus MEDIA WAHID TV dengan watermark yang ditanam **langsung saat pengambilan gambar dan perekaman**, tanpa proses render ulang setelah video selesai.

## Template watermark

### MASJID + MEDIA WAHID TV
- Logo Masjid: kiri atas
- Logo MEDIA WAHID TV: kanan atas

### MEDIA WAHID TV
- Logo MEDIA WAHID TV: kanan atas

Template tersimpan otomatis dan **dikunci selama recording** supaya watermark tidak berubah di tengah video.

## Arsitektur final

v5 tidak lagi membuka Samsung Camera lalu meminta user memilih video dan menunggu render watermark.

Aplikasi sekarang menggunakan CameraX secara langsung:
- Preview, foto, dan video berada dalam satu pipeline.
- OverlayEffect menanam watermark ke frame kamera secara live.
- Video langsung ditulis ke MediaStore/Galeri.
- Tidak ada post-processing video dan tidak ada copy file besar di UI thread.
- Tidak ada duration limit atau file-size limit buatan aplikasi.
- Recording dibuat persistent supaya kamera depan/belakang dapat diganti tanpa sengaja memutus sesi recording.
- Jika pipeline watermark gagal, sistem bersifat fail-closed: recording dihentikan dan hasil yang watermark-nya tidak dapat dijamin tidak dipertahankan.

## Long recording

Target operasional aplikasi adalah recording panjang, termasuk kebutuhan **minimal 1,5 jam**.

Aplikasi tidak memasang batas durasi 90 menit. Durasi nyata tetap bergantung pada kondisi perangkat, terutama:
- ruang penyimpanan,
- baterai/daya,
- temperatur perangkat,
- kemampuan encoder/kamera perangkat.

Sebelum recording:
- di bawah 1 GB kosong: recording diblokir,
- di bawah 8 GB kosong: aplikasi memberi warning untuk kebutuhan recording panjang.

Untuk sesi panjang, aplikasi menjaga layar tetap aktif dan mengunci orientasi selama recording agar pipeline tidak di-recreate karena rotasi.

## Kamera

Fitur utama:
- Video FHD dengan fallback HD/SD jika FHD tidak didukung perangkat.
- Audio bila izin mikrofon tersedia.
- Ganti kamera depan/belakang.
- Pinch-to-zoom.
- Tap-to-focus.
- Torch/flash bila tersedia.
- Timer recording.
- Indikator ukuran file dan sisa storage.
- Foto dengan watermark live.
- Tombol keluar diblokir selama recording/finalizing supaya file tidak rusak.

## Lokasi hasil

Video:
`Galeri → Movies → MEDIA WAHID TV`

Foto:
`Galeri → Pictures → MEDIA WAHID TV`

Nama file:
- `MEDIA_WAHID_TV_DUAL_...`
- `MEDIA_WAHID_TV_MEDIA_...`

## Final safety gates

GitHub Actions harus melewati:
- validasi asset kedua logo,
- pemeriksaan arsitektur CameraX live-watermark,
- larangan terhadap flow lama (video picker, external video capture, post-render Media3, duration/file-size limit),
- clean compile,
- Android lint,
- **10 ronde build ulang APK**,
- integrity check APK pada setiap ronde,
- verifikasi kedua logo tetap masuk ke APK.

Artifact final:
**MEDIA-WAHID-TV-APK-v5-FINAL**

## Acceptance perangkat fisik

CI dapat memastikan source, build, lint, packaging, dan kontrak arsitektur. Validasi terakhir yang memang hanya dapat dilakukan di perangkat adalah satu sesi recording kontinu 90+ menit pada HP target untuk memastikan kondisi hardware spesifik—storage, temperatur, baterai, dan encoder—mendukung durasi tersebut.
