# Pro 1.0.3: pengujian dan batas bukti

Basis proyek: commit `174e0280ccabee5301f174e2d061ae62b4d7454e`, melanjutkan
patch Pro yang ada. Package `tv.mediawahid.camerapro`, versionCode 4.

## Pemeriksaan yang sudah dilakukan di cloud

- Kompilasi, lint delta Java dan 15 unit test: PASS.
- Struktur APK, launcher, release manifest, signing dan zipalign 16 KB: PASS.
- 479 entri asli tetap identik, termasuk seluruh resource terkompilasi dan library
  native. Perubahan bytecode dibatasi service/UI, delta Java dan hook pemberitahuan
  setelah `Recorder.onConfigured` selesai; logika state machine CameraX tidak diubah.
- Dua logo permanen dipulihkan byte-identik dari chunk artwork proyek lama dan
  checksum sumber diverifikasi. Frame video cloud menampilkan logo Masjid kiri
  atas dan MEDIA WAHID TV kanan atas; teks terbaca dan tidak terbalik.
- Adapter state asli pada Android: 37 assertion PASS untuk salinan state,
  timer/byte/audio dan lock konfigurasi. Perubahan timeout setelah pemeriksaan ini
  tidak mengubah adapter state.

## Regresi yang ditemukan dan diperbaiki

STOP saat flip sebelumnya dapat menghasilkan `AssertionError: Unexpectedly invoke
onConfigured() in a STOPPING state` pada CameraX. Penyebabnya STOP mendahului
selesainya setup encoder kamera baru. Aplikasi sekarang menunggu kamera, preview
serta encoder baru, mengantrekan STOP sambil menampilkan Saving, kemudian
mengeksekusi stop pada Recording yang sama. Tidak melakukan stop/start recording
atau resume tersembunyi saat paused.

Capture graph Full HD diberi timeout 60 detik, termasuk setup encoder asinkron;
error kamera tetap langsung memicu upaya rollback. Timeout 15 detik sebelumnya
memicu rollback terlalu dini pada emulator software.

## Pengujian media

Emulator cloud tidak memiliki KVM. Profil bawaan dan sensor belakang tidak
menyediakan FHD; fixture kamera software dan profil FHD disiapkan terpisah tanpa
mengubah aturan kualitas APK. Codec OMX bawaan emulator mengalami native abort.
Percobaan memakai C2 dapat berpindah kamera tetapi assertion pertambahan output
setelah flip belum lulus pada emulator CPU ini. Hasil tersebut **bukan PASS** untuk
kontinuitas video/audio atau performa perangkat fisik.

Workflow Pro 1.0.3 menambahkan pengujian di emulator Android 11 dengan KVM: tujuh
cold launch, adapter state, dua template video, flip berulang, pause/resume,
pertambahan durasi/byte setelah flip, tap cepat, STOP saat recording/paused ketika
switching, pemulihan idle, ekspor MP4, decode, FHD, audio track dan selisih durasi
A/V. Status run aktual harus dibaca di GitHub Actions sebelum menyatakan uji media
lulus. Log, MP4 dan frame hasil disimpan sebagai artifact `install-launch-evidence`.

Fixture memakai software sensor sintetis dan codec C2. Uji ini tidak membuktikan
mikrofon fisik, kualitas sensor Samsung, exposure visual, landscape, panas/memory,
atau ketahanan 90 menit. Matriks fisik di `TESTING.md` tetap berlaku.

## Signing

Keystore Pro 1.0.1 tidak tersedia. Build lokal memakai sertifikat yang cocok
build lokal 1.0.2 (`9c6687090a8d136e23594989a7da23a47f3b8b55d3d376429de39810f1c490d1`).
CI memakai test key sementara. Jangan uninstall instalasi lama untuk memaksa
update: gunakan keystore lama bila perlu mempertahankan data impor/pengaturan.
