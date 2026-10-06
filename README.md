# Chromebox TV

Màn hình chính kiểu TV cho Chromebox chạy ChromeOS. Ứng dụng chạy trong lớp Android của ChromeOS và được thiết kế để điều khiển từ xa trên TV: các ô lớn, đồng hồ to, dùng được phím mũi tên, chuột hoặc remote Bluetooth.

Giao diện theo phong cách Google TV, dựng bằng [Compose for TV](https://developer.android.com/training/tv/playback/compose) (Material 3 for TV) của Google như ứng dụng mẫu JetStream: thanh tab trên cùng (Trang chủ, Ứng dụng, Trang web, Cài đặt, Tìm kiếm), băng chuyền nổi bật tự chạy ở đầu trang chủ và các hàng thẻ bên dưới. Bố cục theo [hướng dẫn thiết kế Android TV](https://developer.android.com/design/ui/tv/guides/foundations/design-for-tv): 960 × 540 dp với lề an toàn 5%, thẻ 16:9 theo lưới 3 và 4 thẻ mỗi hàng, thẻ đang chọn được phóng to, có viền và glow. Phím Quay lại đưa focus về thanh tab, rồi về tab Trang chủ.

## Tính năng

- **Ba hàng ô.** Hàng "Xem ngay" chứa các trang web hay xem. Hàng "Yêu thích" chứa các ứng dụng được ghim. Hàng "Tất cả ứng dụng" chứa mọi ứng dụng Android đã cài.
- **Hình nền.** Chọn trong Cài đặt › Hình nền: ba hình dựng sẵn (Cực quang, Hoàng hôn, Đại dương), nền tối giản, hoặc ảnh của bạn từ trình chọn file của ChromeOS. Ảnh được phủ một lớp tối để chữ và thẻ luôn dễ đọc.
- **Ứng dụng mới cài tự lên Yêu thích.** Giống Android TV, ứng dụng vừa cài được thêm vào đầu hàng Yêu thích và băng chuyền ở Trang chủ. Mở menu của ô để bỏ ghim nếu không muốn.
- **Mở ngay trong launcher.** Ứng dụng và trang web hiện trong chính cửa sổ launcher, bấm Quay lại để về màn hình chính. Trang web phát bằng trình phát dựng sẵn, có chặn popup quảng cáo và xem video toàn màn hình. Mỗi ô web cũng có thể chọn mở bằng Chrome của ChromeOS hoặc 4K Browser.
- **YouTube giao diện TV.** Ô YouTube mở giao diện YouTube dành cho TV ngay trong launcher, điều khiển hoàn toàn bằng remote, đăng nhập bằng mã trên điện thoại qua yt.be/activate. Phím Quay lại điều hướng trong YouTube, nhấn giữ Quay lại để về màn hình chính. Launcher trả lời YouTube về khả năng phát video giống một TV thật, nên YouTube cho chọn tới 4K (2160p60). Video dùng VP9 hoặc H.264 ở dạng SDR, là những định dạng GPU của Chromebox giải mã được. AV1 và HDR được ẩn đi. Nếu máy có app YouTube cho Android TV, như trên Android TV box, launcher mở app đó.
- **Video mới trên YouTube.** Trang chủ có hàng thumbnail video mới nhất từ các kênh bạn theo dõi. Thêm kênh trong Cài đặt › Kênh YouTube trên Trang chủ bằng tên kênh, @tên-kênh hoặc link kênh hay link video. Bấm vào video để xem bằng SmartTube hoặc YouTube giao diện TV. Launcher đọc RSS công khai của từng kênh nên không cần đăng nhập. YouTube không cho xem gợi ý hay danh sách kênh đăng ký nếu không đăng nhập, nên bạn tự chọn kênh.
- **SmartTube.** Cài đặt › SmartTube tải bản chính thức mới nhất của [SmartTube](https://github.com/yuliskov/SmartTube), ứng dụng YouTube mã nguồn mở cho TV có chặn quảng cáo, kiểm tra mã SHA-256 do GitHub công bố rồi cài. Khi đã cài, ô YouTube mở bằng SmartTube, kể cả tìm kiếm bằng giọng nói. Trang chủ có thêm hàng SmartTube để mở thẳng Đăng ký, Lịch sử, Danh sách phát và các mục khác. Muốn quay lại giao diện YouTube TV trên web, chọn trong menu của ô YouTube.
- **Ra lệnh bằng giọng nói qua remote Xiaomi.** Hỗ trợ remote Bluetooth Xiaomi 2 và 2 Pro. Tiếng Việt được nhận dạng ngay trên máy, không cần mạng.
- **Gán nút remote.** Cài đặt › Nút remote: bấm một nút trên remote rồi chọn việc nó làm, như nói lệnh, về Trang chủ, mở tất cả ứng dụng, mở một ứng dụng hay một trang web, bật tắt toàn màn hình, hoặc tắt hẳn nút đó. Nút đã gán dùng được cả khi đang xem trang web. Các phím mũi tên, OK, Quay lại, âm lượng và phím gõ chữ không gán được. Những nút ChromeOS giữ cho riêng nó, thường là micro, Home và nguồn, không đến được launcher nên cũng không gán được.
- **Tự cập nhật.** Ứng dụng tự kiểm tra GitHub Releases và cài bản mới sau một lần xác nhận, không cần cắm cáp hay dùng adb.
- **Toàn màn hình.** Bấm F11 hoặc nút ở góc trên để chuyển chế độ toàn màn hình.

Để mở menu của một ô, bấm phím Menu trên remote, nhấp chuột phải hoặc nhấn giữ. Menu cho phép ghim, đổi thứ tự, sửa hoặc xóa ô. Nếu một ứng dụng chạy không ổn khi mở trong launcher, chọn "Mở trong cửa sổ riêng" trong menu của nó.

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
2. Mở Chromebox TV. Mục Cài đặt › Remote giọng nói chuyển sang "sẵn sàng" khi kết nối xong.
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

**Trên ChromeOS, micro của remote không dùng được.** Android 13 trên ChromeOS chỉ cho ứng dụng hệ thống truy cập dịch vụ giọng nói của remote Android TV (cần quyền `BLUETOOTH_PRIVILEGED`). Launcher nhận ra điều này, ngừng kết nối và báo trong Cài đặt › Remote giọng nói. Giọng nói vẫn chạy trên Android TV box.

Khi không có dịch vụ giọng nói, nút micro của remote Xiaomi gửi phím F5, và ChromeOS dùng F5 để hiện các cửa sổ. Để nút micro mở tìm kiếm, mặc định là SmartTube nếu đã cài, vào Cài đặt ChromeOS › Thiết bị › Bàn phím và phương thức nhập, chọn bàn phím của remote và bật tùy chọn dùng các phím ở hàng trên cùng làm phím chức năng. Trong YouTube giao diện TV, nút micro mở trang tìm kiếm của YouTube. Muốn nút micro làm việc khác, gán nó trong Cài đặt › Nút remote.

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
