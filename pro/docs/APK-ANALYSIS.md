# Analisis referensi Pro 1.0.1

Satu-satunya basis: `Media-Wahid-TV-Camera-Pro-1.0.1.apk` dalam repositori ini.
SHA256: `62afa607bd606479d18bda64a404ace76ca9d4fdb7c634043b7ba6efbf43c367`.
Analisis: JADX 1.5.3, APKTool 2.11.1 dan Androguard 4.1.3. JADX melaporkan 16
kegagalan pada seluruh APK; hasilnya tidak dianggap source lengkap/identik.

- Package `tv.mediawahid.camerapro`, versi 1.0.1/code 2, minSdk 30, target 36.
- CameraX 1.6.2; MainActivity membuat UI programatis.
- LifecycleService CameraRecordingService menangani kamera, audio, foreground
  notification, wake lock dan monitoring storage/thermal/battery.
- ABI arm64-v8a, armeabi-v7a, x86, x86_64 dipertahankan.
- Video FHD SDR, 30 FPS diminta, bitrate 16 Mbps, tanpa mirroring; foto JPEG 95
  dengan target FHD/16:9. Parameter asli tetap dipakai.

## Penyebab flip ditolak

`CameraUiState.getConfigurationLocked()` true selama phase selain IDLE.
MainActivity menonaktifkan Flip berdasarkan lock ini; service.switchCamera()
memiliki guard yang sama. bindCamera() juga menolak saat recording dan jalur
biasa melakukan unbindAll, menutup OverlayEffect, serta membuat Recorder dan
VideoCapture baru. startRecording() sebelumnya tidak memanggil
asPersistentRecording. Mengaktifkan tombol saja tidak cukup.

Perbaikan menambahkan jalur flip recording tersendiri, persistent preparation,
cleanup dan trace event; hanya predicate tombol Flip diubah. Jalur idle/foto
asli tetap tersedia. Adapter mengakses nama field/state aplikasi yang diverifikasi
pada APK terpin, tanpa reflection ke API internal Android. Seluruh 30 component
state dicopy agar timer, byte, audio, template dan lock tetap terjaga.

## Watermark dan preservasi

WatermarkPipeline, WatermarkGeometry, WatermarkStore, pengaturan dan UI asli
dipertahankan. Tidak ada `assets/watermarks/*` pada APK referensi. Store membaca
logo impor galeri atau menggambar badge pengganti secara programatis. Tidak ada
artwork Masjid/Media Wahid TV yang dapat diekstrak dari APK ini.

Setelah perakitan, 480 entri asli disalin identik: seluruh resource terkompilasi,
gambar, library native, DEX lain dan metadata. Hanya AndroidManifest.xml dan
classes3.dex diganti; classes5.dex memuat delta. Kesamaan entri tidak membuktikan
kesamaan visual di seluruh perangkat; rebuild tidak diklaim identik biner.

## Durasi panjang

Tidak ditemukan batas 29 detik pada RecordingSafety/service yang ditinjau.
Aslinya ada syarat ruang mulai 512 MiB, cadangan 128 MiB/15 detik estimasi bitrate,
serta monitoring thermal/battery. Tidak ditambahkan batas durasi video.
Timeout 15 detik hanya untuk perpindahan kamera. Klaim 90 menit memerlukan
pengujian fisik dengan storage, suhu dan baterai memadai.
