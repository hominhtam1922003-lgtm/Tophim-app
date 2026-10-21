# App Đọc Truyện Tranh — TruyenQQ (Android, Kotlin)

App đọc truyện tranh lấy dữ liệu trực tiếp từ **truyenqqko.com**,
build APK hoàn toàn bằng **GitHub Actions** — không cần Android Studio.

## Tính năng

- **Giao diện hiện đại**: Material 3, lưới bìa truyện bo góc, thanh điều
  hướng dưới 3 tab: Trang chủ / Yêu thích / Lịch sử.
- **Tự cập nhật truyện mới**: Trang chủ tải danh sách mới nhất từ
  truyenqqko.com mỗi lần mở, kéo xuống để làm mới, nút "Tải thêm" để xem
  tiếp các trang sau. Có ô tìm kiếm.
- **FULL CHAP**: trang chi tiết của web đã chứa sẵn toàn bộ danh sách
  chương trong HTML (đã kiểm chứng: One Piece 1.196 chap hiện đủ 1.241
  link chương ngay khi tải trang — nút "Xem Thêm" trên web chỉ mở rộng
  phần mô tả, không tải thêm chương). App quét toàn bộ link chương trong
  trang nên lấy đủ 100% số chap trong 1 lần tải, sắp xếp tăng dần.
- **Đọc truyện**: xem ảnh cuộn dọc, chuyển Chap trước / Chap sau.
- **Lịch sử đọc**: tự lưu chương đã đọc, nút "Đọc tiếp" trong trang chi
  tiết, tab Lịch sử xem lại.
- **Yêu thích**: bấm ☆ trong trang chi tiết để lưu truyện.
- **Thông báo chap mới**: WorkManager kiểm tra định kỳ mỗi 3 giờ (khi có
  mạng) các truyện yêu thích; có chap mới hơn mốc đã lưu sẽ gửi thông
  báo, chạm vào thông báo mở thẳng trang truyện.
- **Dự phòng Cloudflare**: web có lớp chống bot. App tải bằng Jsoup với
  header trình duyệt thật; nếu bị chặn (trang "Just a moment") sẽ tự thử
  lại bằng WebView thật ngay trong app.

## Cách ra APK

1. Đẩy toàn bộ thư mục này lên một repo GitHub (giữ nguyên cấu trúc,
   đặc biệt là `.github/workflows/`).
2. Vào tab **Actions** → đợi workflow **Build APK** chạy xong
   (khoảng 3–6 phút).
3. Mở lần chạy đó → mục **Artifacts** → tải **truyen-reader-apk** →
   giải nén được `app-debug.apk` → cài vào điện thoại Android.

Mỗi lần sửa code rồi push lên nhánh `main`, Actions tự build APK mới.

## Cấu trúc code chính

- `TruyenQQSource.kt` — tải & phân tích HTML nguồn (danh sách, chi tiết
  + full chap, ảnh chương). Mọi selector đều dựa trên mẫu URL đã kiểm
  chứng, không phụ thuộc class CSS của web.
- `WebFetcher.kt` — tải trang qua WebView khi bị Cloudflare chặn.
- `DbRepo.kt` — SQLite lưu Yêu thích + Lịch sử (không cần tài khoản).
- `NewChapterWorker.kt` — kiểm tra chap mới nền + gửi thông báo.
- `MainActivity.kt` + `HomeFragment.kt` / `ListFragments.kt` — giao diện
  3 tab. `DetailActivity.kt`, `ReaderActivity.kt` — chi tiết & đọc.

## Lưu ý

- Đây là APK debug: cài dùng thử thoải mái, chưa đăng Google Play được
  (bản release cần keystore ký — làm thêm khi cần phát hành).
- Nguồn truyện thuộc về truyenqqko.com; web đổi cấu trúc HTML hoặc tăng
  mức chặn bot có thể khiến app cần cập nhật lại bộ phân tích.
- Quyền thông báo: trên Android 13+ app sẽ xin quyền POST_NOTIFICATIONS
  khi mở lần đầu; từ chối thì mọi tính năng khác vẫn dùng bình thường,
  chỉ không có thông báo chap mới.

## Ban 2.1

- Fix loi anh khong tai duoc: gan Referer + User-Agent trinh duyet cho moi anh (CDN cua nguon chan tai anh tran).
- Fix tim kiem: tu do form tim kiem that tren trang chu + cac endpoint du phong.
- Full chuong: WebView du phong dung giao dien desktop, tu bam "Xem them"; ke ca khi Jsoup tai duoc nhung thieu chuong so voi "Tong so chap", app van thu lay ban day du hon.

## Ban 2.2

- Lich su gop theo truyen: moi truyen 1 dong (chuong doc gan nhat + so chuong da doc).
- Them tab Moi (truyen moi cap nhat, phan trang day du) va tab Hot.
- Giao dien gon: luoi 3 cot, the truyen nho hon, hien duoc nhieu truyen hon.
- Chan quang cao ngay trong WebView du phong (blocklist ten mien ads), tat mo cua so popup.
- Xin lai quyen thong bao khi vao tab Yeu thich (Android 13+); them quyen ACCESS_NETWORK_STATE.
