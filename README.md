# Chromebox TV

Màn hình chính kiểu TV cho Chromebox chạy ChromeOS. Ứng dụng chạy trong lớp Android của ChromeOS và được thiết kế để điều khiển từ xa trên TV: các ô lớn, đồng hồ to, dùng được phím mũi tên, chuột hoặc remote Bluetooth.

## Tính năng

- **Ba hàng ô.** Hàng "Xem ngay" chứa các trang web hay xem. Hàng "Yêu thích" chứa các ứng dụng được ghim. Hàng "Tất cả ứng dụng" chứa mọi ứng dụng Android đã cài.
- **Mở trang web theo trình duyệt tùy chọn.** Mỗi ô web mở bằng Chrome của ChromeOS hoặc bằng 4K Browser nếu đã cài.
- **Ra lệnh bằng giọng nói qua remote Xiaomi.** Hỗ trợ remote Bluetooth Xiaomi 2 và 2 Pro. Tiếng Việt được nhận dạng ngay trên máy, không cần mạng.
- **Tự cập nhật.** Ứng dụng tự kiểm tra GitHub Releases và cài bản mới sau một lần xác nhận, không cần cắm cáp hay dùng adb.
- **Toàn màn hình.** Bấm F11 hoặc nút ở góc trên để chuyển chế độ toàn màn hình.

Để mở menu của một ô, bấm phím Menu trên remote, nhấp chuột phải hoặc nhấn giữ. Menu cho phép ghim, đổi thứ tự, sửa hoặc xóa ô.

## Cài đặt

1. Trên Chromebox, bật cài ứng dụng Android ngoài Play: mở Cài đặt › Giới thiệu về ChromeOS › Nhà phát triển › Môi trường phát triển Linux › Phát triển ứng dụng Android, rồi bật Gỡ lỗi ADB. Tên các mục có thể khác đôi chút tùy phiên bản ChromeOS.
2. Tải file `Chromebox-TV-x.y.z.apk` ở trang [Releases](https://github.com/tuchung95/chromebox-tv-launcher/releases/latest).
3. Cài bằng adb từ một máy tính khác trong cùng mạng:

   ```
   adb connect <địa-chỉ-IP-Chromebox>:5555
   adb install Chromebox-TV-x.y.z.apk
   ```

4. Ghim Chromebox TV vào kệ ứng dụng. Để nó tự mở khi đăng nhập, chọn Cài đặt › Tùy chọn hệ thống › Khởi động › Khôi phục ứng dụng khi khởi động › Luôn luôn, và để Chromebox TV mở khi tắt máy.

Các lần sau, bấm nút Cập nhật trong ứng dụng là đủ. Lần cập nhật đầu tiên, Android sẽ xin quyền cho Chromebox TV cài ứng dụng.

## Remote giọng nói

1. Ghép đôi remote Xiaomi trong Cài đặt › Bluetooth của ChromeOS.
2. Mở Chromebox TV. Nút Remote ở góc trên chuyển sang "sẵn sàng" khi kết nối xong.
3. Bấm nút micro trên remote rồi nói.

| Câu nói | Kết quả |
|---|---|
| mở YouTube | Mở ô YouTube |
| mở VLC | Mở ứng dụng VLC |
| tìm trên YouTube nhạc Trịnh | Tìm "nhạc Trịnh" trên YouTube |
| nhạc Trịnh trên YouTube | Như trên |
| tìm trên Google thời tiết Hà Nội | Tìm trên Google |
| phim hành động | Hỏi bạn muốn tìm ở đâu |

Một ô web tìm kiếm được bằng giọng nói khi ô đó có địa chỉ tìm kiếm chứa `%s`, ví dụ `youtube.com/results?search_query=%s`.

Kết nối remote được thử theo đặc tả giao thức, nhưng chưa được kiểm chứng trên mọi phiên bản ChromeOS. Nếu nút Remote báo "chưa ghép đôi" dù remote đã ghép, hãy chọn đúng remote trong mục Remote › Chọn remote.

## Build từ mã nguồn

Cần JDK 17 và Android SDK 36.

```
./gradlew testDebugUnitTest assembleRelease
```

Lần build đầu tự tải model tiếng Việt khoảng 32 MB và kiểm tra mã SHA-256 của file.

Bản phát hành được ký bằng khóa riêng đọc từ `~/.android/chromebox-tv-release.properties`. Nếu không có file này, bản build dùng khóa debug. Một bản ký bằng khóa khác không cập nhật đè được bản đang cài.

Để phát hành phiên bản mới, chạy lệnh sau. Script nâng số phiên bản, chạy test, build và ký APK, gắn tag rồi tạo GitHub Release kèm `updates.json`:

```
scripts/release.sh "Nội dung thay đổi"
```

## Ghi công

- [Vosk](https://alphacephei.com/vosk/) và model `vosk-model-small-vn-0.4` để nhận dạng tiếng Việt, giấy phép Apache 2.0.
- Giao thức micro của remote là ATV Voice over BLE của Google. Dự án [SayAll / remote-mic-app](https://github.com/HD838A/remote-mic-app) được dùng làm tài liệu tham khảo về cách remote Xiaomi triển khai giao thức. Mã nguồn ở đây được viết riêng, không sao chép từ dự án đó.
