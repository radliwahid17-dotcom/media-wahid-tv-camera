# Pro 1.0.4: kontinuitas encoder saat flip

Basis tetap APK Pro 1.0.1, package `tv.mediawahid.camerapro`, pada branch
`codex/pro-1.0.2-flip` repositori `media-wahid-tv-camera`. Resource, layout,
library native, kualitas produksi FHD SDR 30 FPS/16 Mbps, dan dua artwork permanen
dipertahankan. VersionCode 5. Tidak ada perekaman segmen atau penggabungan video.

**Status: APK berhasil dibangun, dipasang dan dibuka; target kelancaran belum
lulus seluruh pengujian. Definition of done seamless belum terpenuhi.**

- [APK 1.0.4 dan checksum/provenance](https://github.com/radliwahid17-dotcom/media-wahid-tv-camera/actions/runs/39007711587/artifacts/12091478520).
- [CI kode `d73a061`](https://github.com/radliwahid17-dotcom/media-wahid-tv-camera/actions/runs/39007711587): build SUCCESS, runtime FAIL untuk timing.
- [MP4, JPEG playback, JSON hasil dan logcat](https://github.com/radliwahid17-dotcom/media-wahid-tv-camera/actions/runs/39007711587/artifacts/12092210459).

Ekstrak artifact dan gunakan `Media-Wahid-TV-Camera-Pro-1.0.4-SEAMLESS.apk`.
`logo-tests.apk` adalah harness instrumentation, bukan aplikasi kamera.

## Penyebab dan perbaikan

`asPersistentRecording()` mempertahankan objek Recording, tetapi pelepasan use case
tetap mengakhiri SurfaceRequest kamera. Recorder dapat kehilangan input atau
membuat ulang encoder. PreviewView juga dapat melaporkan STREAMING dari kamera
sebelumnya sehingga tombol flip terbuka terlalu cepat.

`StableVideoOutput` sekarang menahan satu SurfaceRequest encoder dan satu Recorder.
`StableFrameRenderer` menerima surface kamera yang dapat diganti dan mempertahankan
clock output monotonik. `VideoWatermarks` memakai artwork, settings,
`WatermarkGeometry` dan `drawLogo()` asli untuk membuat satu tekstur watermark
yang dikomposisi setelah normalisasi/blend kamera. Transform StreamSharing preview
tidak lagi menentukan posisi logo video. Tidak ada logo yang digambar ulang.
Dalam mode VIDEO, OverlayEffect asli menarget PREVIEW (1); watermark video
dikomposisi sekali pada endpoint encoder tetap. Dalam mode PHOTO, effect asli
tetap memakai mask 7 untuk preview/foto. Ukuran, margin, opacity dan template
mengikuti kode Pro asli; template tetap terkunci selama recording.
Jalur live menggambar tekstur OES kamera langsung ke surface encoder. Buffer
RGBA hanya dipakai untuk snapshot transisi/fallback saat producer dilepas;
salinan GPU satu frame penuh pada setiap callback kamera dihilangkan. Input
baru tetap dinormalisasi ke orientasi encoder pertama; logo dikomposisi sekali
setelah kamera/blend sehingga tidak ikut berputar atau mengganda.
Area transparan di luar bounding box alpha artwork tidak diraster ulang setiap
frame. Bounding box dihitung sekali dari bitmap asli; padding dua pixel menjaga
tepi hasil filter/antialias. Posisi, ukuran, opacity dan pixel logo tidak diubah.
MediaCodec dikonfigurasi memakai opsi Android
`KEY_REPEAT_PREVIOUS_FRAME_AFTER` 40.000 mikrodetik untuk target asli 30 FPS
dan prioritas realtime. Timeout 1,2 periode menghindari pengulangan buffer yang
tidak perlu akibat jitter kecil pada input kamera normal.
Surface encoder dapat mengulang buffer terakhir tanpa upload GPU tambahan.
Sebagian codec membatasi jumlah repeat berturut-turut. Renderer memperbarui
buffer yang ditahan setiap 100 ms agar repeat tidak kehabisan jatah ketika sensor
belum tersedia; ini tetap bukan frame sensor baru. Pada input normal 30 FPS tidak
ada upload duplikat tambahan. Renderer mengunggah frame baru serta langkah blend; saat sensor berpindah,
frame terakhir ditahan;
setelah frame kamera tujuan datang, blend berlangsung 120 ms. Jeda sensor tetap
ada dan dicatat; ini bukan jaminan zero-gap atau 30 frame sensor baru per detik.
Audio dan muxer tetap dikelola Recorder asli.
Clock synthetic/composed source dimulai dari waktu monotonic saat komposisi,
sejalan dengan microphone dan batas pause EncoderImpl. Timestamp sensor pertama
yang terlambat tidak dijadikan offset permanen untuk seluruh sesi; timestamp
sensor tetap digunakan dalam logging gap/usia frame. Sinkronisasi ucapan pada
Samsung masih harus diuji karena pipeline kamera juga mempunyai latency fisik.

CameraX StreamSharing dapat mengingat surface repeating anak yang telah ditutup
saat use case ACTIVE di-bind kembali. Bridge memperbarui state graph kamera melalui
observable stream, tanpa memanggil Recording.pause/resume/stop. Ini meminta koneksi
surface baru; state Recorder dan audio tidak dipause.

Saat pengguna memilih pause manual, Recorder dapat menandai stream INACTIVE.
Bridge mempertahankan state ACTIVE hanya pada graph kamera agar preview dan
frame pertama kamera tujuan tetap tersedia. Recording dan audio tetap PAUSED.
Input kompositor terus diberikan supaya EncoderImpl melihat buffer yang melewati
batas pause; EncoderImpl asli membuang buffer selama interval pause dan mengatur
`drop-input-frames`. Menghentikan input GL sebelum batas itu sempat dilewati dapat
mengacaukan timing resume. Tidak ada resume otomatis atau sampel pause yang sengaja
dimasukkan ke file akhir.
State kamera dipulihkan dari Recorder saat STOP/Finalize/release; state error
encoder tidak ditutupi oleh pemetaan ini.

`PersistentFlip` memeriksa kemampuan kamera di worker, menjalankan bind/unbind di
main thread sesuai kontrak CameraX, serta menunggu kamera OPEN dan frame dari lensa
tujuan. Tap ganda diblokir sampai blend selesai. Timeout/error mencoba kamera
sebelumnya dengan Recording yang sama. STOP dan Finalize membatalkan callback lama;
surface/tekstur dilepas setelah CameraX mengembalikannya.

## File utama

- `flip/src/main/java/tv/mediawahid/camerapro/flip/`: `StableVideoOutput`,
  `StableFrameRenderer`, `FrameCadence`, `PersistentFlip`, `RecordingTrace`,
  `MuxerCadenceEncoder`, `MuxerTimestamp`, `VideoWatermarks`.
- `flip/src/main/java/androidx/camera/video/MWTVEncoderFactory.java` dan
  `internal/encoder/MWTVRepeatingVideoConfig.java`: adapter konfigurasi encoder.
- `tools/patch_baseline.py` dan `tools/rebuild.py`: integrasi service asli.
- `tools/android-tests/VideoInstrumentation.java`, `tools/video_device_test.py`:
  recording/flip/MediaStore/playback nyata dari APK terpasang.
- `tools/runtime_suite.py` dan workflow `pro-flip.yml`: build dan bukti pengujian.

CameraX dipin 1.6.2. Bridge memakai API library-group SurfaceRequest/VideoOutput
dan dua class adapter akses package untuk factory EncoderImpl asli. Class asli
CameraX tidak diganti atau diedit. Semua pengaturan resolusi, bitrate, profil,
data space, audio dan muxer tetap didelegasikan. `MuxerCadenceEncoder` mewarisi
EncoderImpl asli agar lifecycle dan signalSourceStopped tetap bekerja.
Timestamp frame yang terlalu berdekatan akibat tabrakan repeat/input diberi
jarak minimal 1 ms sebelum muxer, sehingga tetap unik setelah pembulatan timescale
MP4. Frame AVC tidak dibuang; rantai referensi kompresi dipertahankan. Koreksi
di atas 5 ms ditolak sebagai diskontinuitas clock dan dilaporkan sebagai error.
Upgrade CameraX harus diuji ulang.
Tidak ada refleksi ke internal CameraX/Android.
Adapter refleksi hanya mengakses class Pro yang dipin dari APK, termasuk geometry
dan Canvas drawing asli. Tidak mengakses internal CameraX/Android.

## Cakupan validasi

Build Gradle lokal, lint delta, 18 JUnit, APKTool, apksigner, zipalign, dan pemeriksaan
struktur lulus. Verifier memeriksa 480 entri APK asli tetap byte-identik, semua
resource terkompilasi/native, kedua PNG beserta SHA256/CRC, identitas dan launcher.

Uji native video menyediakan fixture HD khusus emulator karena profil FHD tidak
diiklankan. Fixture menyuntikkan use case ke service APK final; ia tidak mengubah
konfigurasi produksi. Preview fixture memakai VGA untuk membatasi beban software
renderer, tetapi file video tetap HD. Recorder tetap memilih audio dari profil
perangkat, seperti produksi; emulator yang dipakai mengiklankan AMR. Percobaan
fixture AAC adalah diagnostik yang gagal dan sudah dihapus, bukan bukti uji audio
Samsung. Tidak ada override format audio dalam APK akhir.
Perekaman, microphone, watermark, flip, pause/resume,
MediaStore dan finalization memakai implementasi APK terpasang. Observer state/display
Activity dilepas hanya dalam fixture video HD agar UI tidak memasang kembali
pipeline produksi FHD yang tidak tersedia di emulator. PreviewView asli tetap
memberikan surface. Landscape menguji orientasi hasil enkode; rotasi UI otomatis
dan siklus UI FHD setelah STOP tetap membutuhkan Samsung fisik. Rencana uji:

- Rekaman 30 detik, flip setelah 10 dan 20 detik, template dua logo.
- Rekaman 180 detik, 20 flip, template satu logo, tap ganda setiap flip.
- Pause manual, flip saat paused, resume, STOP lalu mulai sesi baru.
- Pipeline landscape, dua flip, decode orientasi dan pixel logo di MP4.
- Identitas Recording/Recorder/VideoCapture, timer, track audio/video, PTS,
  durasi A/V, decode MP4, gambar tidak hitam dan pixel artwork di frame hasil.
- Perubahan isi kamera emulator yang beranimasi diperiksa pada frame playback;
  output dengan timestamp maju tetapi gambar membeku permanen ditolak.

Assertion timing tetap video gap <250 ms, audio packet gap <100 ms dan selisih
durasi A/V <500 ms. Kegagalan timing dikumpulkan setelah seluruh sesi, lalu test
tetap mengembalikan exit gagal. Assertion tidak dilonggarkan untuk emulator.
Log `Session frames` menghitung upload renderer; sample video hasil enkode
termasuk pengulangan native dilaporkan terpisah oleh MediaExtractor.

Run penuh `39007711587` menjalankan `quick=false`, empat rekaman dan 26 flip.
Sesi stress berisi 20 flip dalam 180 detik; hasilnya satu MP4 dengan span video
180,033 detik dan audio 180,040 detik. Recording, Recorder dan VideoCapture tetap
objek yang sama, timer tidak reset, tap kedua diblokir, dan keempat Finalize
melaporkan error 0. Semua file dapat dibaca MediaExtractor dan didekode; 12 frame
playback lulus pixel logo sesuai template/orientasi serta pemeriksaan gambar
tidak hitam dan gerak scene emulator. Itu tidak membuktikan bebas freeze pada
setiap frame: saat sensor berganti, frame terakhir memang ditahan.

| Sesi | Flip | Gap PTS video maksimum | Gap paket audio maksimum | Selisih span A/V | Logo/playback |
| --- | ---: | ---: | ---: | ---: | --- |
| Normal, template A | 2 | 266,667 ms — FAIL | 80 ms — PASS | 20 ms — PASS | PASS |
| 180 detik, template B | 20 | 300 ms — FAIL | 80 ms — PASS | 6,667 ms — PASS | PASS |
| Pause/flip/resume, A | 2 | 233,334 ms — PASS | 120,125 ms — FAIL | 153,286 ms — PASS | PASS |
| Landscape, A | 2 | 200 ms — PASS | 80 ms — PASS | 80 ms — PASS | PASS |

Batas tetap video <250 ms, audio <100 ms dan selisih span A/V <500 ms.
Tiga pemeriksaan timing gagal; hasil native adalah `failed-native-timing`, bukan
PASS. Counter 8.088 assertion mencakup pemeriksaan yang gagal tersebut.
Swap buffer terukur hingga 206,4 ms pada renderer software emulator. Penyebab
seluruh gap pada Samsung belum bisa dipastikan dari emulator; tidak ada klaim
zero-gap, 30 frame sensor baru/detik, atau sinkronisasi ucapan Samsung.

Pengujian CI lain lulus: compile, lint delta Java, 18 JUnit, verifikasi APK/signature/
zipalign, tujuh cold launch tanpa crash, 37 assertion state adapter, 32 kasus
Canvas logo, serta empat JPEG offline dari dua kamera/dua template. Pemeriksaan
JPEG memakai fallback resolusi emulator dan tidak membuktikan FHD foto. APK lokal
juga berhasil dipasang/dibuka pada emulator TCG; uji performa TCG yang timeout
sebelumnya tidak diklaim lulus. Build sukses tidak dianggap bukti rekaman lancar.

Riwayat diagnosis (hasil gagal tidak dianggap PASS):

- Timeout awal menemukan StreamSharing yang mempertahankan surface lama, readiness
  dari preview lama, serta graph kamera yang INACTIVE ketika pengguna pause.
- Repeat codec yang terbatas memerlukan heartbeat. Tabrakan timestamp repeat/live
  memerlukan penjagaan keunikan PTS tanpa membuang frame AVC.
- Pixel logo landscape yang gagal memicu komposisi watermark setelah normalisasi
  kamera. Pengujian berikutnya meluluskan logo pada kedua orientasi.
- Run diagnostik singkat `39004158918` dan `39004805551` tetap gagal timing;
  log mengukur input GL 330 ms dan swap 201 ms pada renderer software.
- Percobaan AAC `39005546908` gagal: audio hanya 4,3–7,6 detik untuk video
  7,6–14,8 detik. Override audio dihapus, profil asli dikembalikan. Hasil itu
  tidak dipakai untuk mengklaim dukungan/sinkronisasi AAC Samsung.
- Jalur live kemudian memakai OES langsung dan area alpha artwork untuk
  mengurangi beban GL, disertai pengulangan pengujian penuh.

Samsung fisik masih wajib untuk FHD portrait/landscape, kualitas/exposure, ucapan
dengan sinkronisasi bibir, UI/zoom/brightness/exposure setelah flip, STOP di tengah
switch/recovery hardware, siklus UI setelah STOP, serta 90 menit
dengan pemantauan suhu/memory/storage. Audio emulator hening hanya memvalidasi
track dan timestamp. Tidak ada batas durasi buatan yang ditambahkan.

Samsung fisik tidak tersambung pada lingkungan ini. Pengujian tersebut belum
dijalankan; kelancaran dan sinkronisasi pada Samsung tetap belum terverifikasi.

Kunci asli 1.0.1 tidak tersedia. APK ditandatangani kunci uji di luar Git;
sertifikat berbeda tidak bisa menimpa instalasi lama. Package tidak diganti.
Checksum dan provenance APK disertakan dalam artifact CI.
