# Kiến trúc

Theo [Guide to app architecture](https://developer.android.com/topic/architecture) của Android.

```
UI layer                          Data layer
┌──────────────────────┐          ┌───────────────────────────────┐
│ Fragment (XML view)  │ observe  │ Repository                    │
│        ▲             │◄─────────│   ├─ Room DAO (offline)       │
│        │ StateFlow   │          │   ├─ SocialChannelClient      │
│ ViewModel ───────────┼─────────►│   └─ LlmClient / ContentAgent │
└──────────────────────┘  gọi hàm └───────────────────────────────┘
```

- **Fragment** chỉ hiển thị và chuyển sự kiện người dùng cho ViewModel.
- **ViewModel** giữ `UiState`, gọi repository/interface, không biết đến Android View.
- **Repository / Client** nói chuyện với mạng, DB, WorkManager.
- **Offline-first:** UI đọc từ Room; mạng chỉ cập nhật Room. Nhờ đó app vẫn xem được dữ liệu khi mất mạng (F9).
  Repository của từng tính năng inject DAO (`DraftDao`, `PostDao`, `CommentDao`, `MetricsDao`) từ `di/DatabaseModule.kt`,
  đọc bằng `Flow` và đổi entity ↔ model qua `core/database/Mappers.kt`. Riêng bài đăng chưa có model trong
  `core/model`, nên repository F5 dùng thẳng `PostEntity` (muốn có model `Post` thì mở issue `contract`). Đổi schema thì tăng `version` trong
  `AppDatabase`, thêm migration và commit file mới trong `app/schemas/`.

## Hợp đồng chung (`core/`)

Các mảng giao tiếp với nhau **chỉ qua interface trong `core/`**:

| Interface | Chủ | Ai gọi |
|---|---|---|
| `core/social/SocialChannelClient` | B | F5 đăng bài (A), F6 comment (B), F7 số liệu (D) |
| `core/ai/LlmClient` | C | ContentAgent (C), F6 phân loại + soạn trả lời (B) |
| `core/ai/ContentAgent` | C | F3 nhập topic, F4 duyệt bài (D) |

Mỗi interface có bản giả trong `data/fake/`, được gắn bằng `di/FakeDataModule.kt`. Nhờ vậy cả nhóm code song song
được: khi bản thật xong, chủ mảng chỉ thay hàm `@Provides` tương ứng, code gọi không phải sửa.

```
D (F3 nhập topic) ──► ContentAgent (C)
D (F4 duyệt bài)  ──► ContentAgent (C)        rewrite / sinh lại ảnh
D (F4 bấm Đăng)   ──► F5 hàng đợi (A) ──► SocialChannelClient (B)
B (F6 comment)    ──► LlmClient (C)           phân loại, soạn trả lời
D (F7 dashboard)  ──► Room snapshot (A) ◄── job lấy số liệu (B)
```

## Quy tắc thay đổi hợp đồng

1. **Thêm** hàm/field mới (có giá trị mặc định): mở PR bình thường, chủ interface phải nằm trong 2 người review.
2. **Đổi hoặc xoá** (breaking change):
   - Mở issue nhãn `contract`, ghi lý do và phương án.
   - Cả 4 thành viên đồng ý trên issue.
   - Người đề xuất sửa **toàn bộ chỗ gọi** trong cùng một PR.
3. Hàm sắp bỏ: đánh dấu `@Deprecated("…", ReplaceWith("…"))` trước, xoá ở milestone sau.
4. `.github/CODEOWNERS` tự yêu cầu review của chủ sở hữu khi có thay đổi trong `core/`.

## Cấu trúc package

```
com.lttbdd.socialagent
├── core/
│   ├── model/        Draft, Comment, PostMetrics, ChannelType, AgentEvent...
│   ├── social/       SocialChannelClient
│   ├── ai/           LlmClient, ContentAgent
│   ├── database/     Room DB + DAO + entity + mapper (A)
│   └── ui/           component dùng chung (D)
├── data/
│   ├── fake/         bản giả của các interface
│   ├── social/       FacebookClient, LinkedInClient (B)
│   └── ai/           OpenAiClient, GeminiClient, ContentAgentImpl (C)
├── di/               Hilt module
└── feature/
    ├── channels/     F1 Kênh của tôi (B)
    ├── agent/        F2 tiến độ + nhật ký agent (C)
    ├── compose/      F3 nhập topic (D)
    ├── review/       F4 duyệt bài (D)
    ├── publishing/   F5 đăng bài + hàng đợi (A)
    ├── comments/     F6 hộp thư comment (B)
    ├── analytics/    F7 dashboard (D)
    ├── apikeys/      F8 BYOK (C)
    └── settings/     F9 cài đặt (A)
```

## Đăng nhập LinkedIn qua BFF (ngoại lệ Quy tắc 5, #88)

Native PKCE của LinkedIn từ chối scope `openid`, nên App Member dùng Standard OAuth qua một BFF nhỏ ở **repository backend riêng** ([social-agent-linkedin-bff](https://github.com/dhminh678/social-agent-linkedin-bff); repository này không chứa mã backend). Facebook không đi qua BFF: app dùng Meta SDK.

```
App ──POST /auth/linkedin/start (state, code_challenge)──► BFF
App ──mở trình duyệt hệ thống──► LinkedIn ──redirect──► BFF /auth/linkedin/callback
BFF ──đổi code lấy token (giữ client_secret)──► LinkedIn
BFF ──302 deep link com.lttbdd.socialagent://oauth/linkedin/callback?exchange_code&state──► App
App ──POST /auth/linkedin/token (exchange_code, code_verifier)──► BFF ──access token + expires_in──► App
```

- Token chỉ nằm trong bộ nhớ BFF tối đa 60 giây và bị xóa ngay lần đổi đầu tiên; BFF không ghi đĩa/DB, không log token, `code`, `state`, `code_verifier` và không chuyển tiếp `refresh_token`.
- App kiểm `state` ở mọi nhánh của deep link và chỉ đổi `exchange_code` kèm `code_verifier` còn trong RAM. Token được lưu bằng `SecureTokenStore` như các kênh khác.
- Không có `/refresh` hay `/logout`: token LinkedIn hết hạn thì người dùng đăng nhập lại.
- Redirect dùng custom scheme (bản demo). Rủi ro phishing của custom scheme là known issue; sẽ chuyển sang Android App Link bằng ticket riêng.
- Chi tiết quyết định và điều kiện: issue #88 và `docs/research-social-api.md` (quyết định 4, Luồng B).

### Làm mới kết nối Facebook (issue #24)

- App kiểm tra refresh khi Activity chính ở foreground, kể cả khi người dùng chuyển tab. LinkedIn vẫn đăng nhập lại khi token hết hạn theo luồng BFF ở trên. `MainActivity` và màn Kênh dùng chung `ChannelViewModel` phạm vi Activity để chia sẻ kết quả refresh. Token đang chờ chọn Page và dữ liệu bộ chọn nằm trong RAM của ViewModel, có thể tồn tại khi view của Fragment được tạo lại.
- Token Page lưu hạn là `null` khi chưa có hạn thực từ Meta. Hạn phiên người cấp quyền dùng để lên lịch refresh và nhắc kết nối lại. Phản hồi `/me/accounts` luôn lưu hạn Page là `null`, kể cả khi chuỗi Page token trùng với lần trước. Nếu cần triển khai xác minh hạn Page qua [Meta Debug Token](https://developers.facebook.com/docs/graph-api/reference/debug_token/), cần cấu hình xác thực được Meta hỗ trợ. APK hiện không nhúng App Secret.
- Page đã lưu từ bản cũ thiếu ID người cấp quyền sẽ hiện **Cần kết nối lại** để cập nhật thông tin bằng phiên đăng nhập đã xác nhận. Sự cố mạng, SDK hoặc kho dữ liệu được phân loại để thử lại. Khi Meta xác nhận phiên không hợp lệ, chỉ các kênh của phiên liên quan được yêu cầu kết nối lại. Khi hạn đã lưu của phiên người cấp quyền đến, Page hiện **Cần kết nối lại** và có nút tương ứng ngay cả khi SDK không còn phiên. Kết quả refresh báo phiên hết hạn cũng hiện lời nhắc cho các Page bị ảnh hưởng. Lời nhắc này không đánh dấu Page token hết hạn hoặc vô hiệu hóa ma trận quyền.
- Repository tuần tự hóa các lượt refresh SDK tự động và dùng đồng hồ đơn điệu để tính thời gian chờ theo phiên và theo Page. App kiểm tra thế hệ phiên trước và sau callback SDK, rồi kiểm tra lại trước khi xác nhận token đã ghi tạm. Màn Kênh giữ thông báo lỗi tạm thời trong thời gian chờ. Log `FacebookTokenRefresh` ghi mã kết quả, số kênh, khoảng chờ và mã lỗi Graph/HTTP khi SDK có cung cấp. Log không chứa token, ID tài khoản, nội dung lỗi hay phản hồi API.
- Page vắng khỏi danh sách trả về có cảnh báo và nút kết nối lại riêng. Token và quyền đã lưu của Page được giữ nguyên vì phản hồi không xác nhận nguyên nhân. Kết quả refresh ghi lỗi theo tài khoản, và một lần lưu thất bại không hoàn tác những Page đã lưu thành công.
- Khi Activity rời foreground hoặc hết 30 giây chờ SDK, coroutine ngừng nhận kết quả. Lượt bị hủy do vòng đời Activity không tạo thời gian chờ mới. Nếu SDK hết thời gian chờ, lỗi timeout vẫn áp dụng backoff. SDK 17.0.2 có thể hoàn tất request sau đó, nhưng callback muộn không tiếp tục tải Page hay ghi token. Một giao dịch token đã bắt đầu vẫn hoàn tất theo cơ chế ghi an toàn của kho.
- Retry tự động mặc định chờ 15 phút. Lỗi tạm thời liên tiếp của SDK hoặc của cả đợt refresh tăng dần thời gian chờ đến tối đa 2 giờ. Lỗi của từng Page không làm tăng thời gian chờ tự động. Rate limit áp dụng khoảng chờ chung 30 phút cho cả refresh tự động và retry Page. Phiên không hợp lệ, hết hạn hoặc sai tài khoản sẽ chờ phiên SDK thay đổi. Lệnh refresh SDK trực tiếp có chốt chống gọi đồng thời riêng; app vẫn áp dụng backoff sau lỗi SDK.
- Màn Kênh kiểm tra Page token đã lưu qua `GET /me?fields=id` khi màn hình ở foreground, với khoảng chờ 15 phút sau lượt hoàn tất hoặc khi Meta báo giới hạn tốc độ. Lượt bị hủy giữa chừng có thể chạy lại ngay. Lỗi 190 (gồm 458 và 460) yêu cầu kết nối lại đúng Page. Subcode 492 hiện hướng dẫn kiểm tra quyền và Page task. Ma trận khả năng dùng cùng kết quả kiểm tra để hiển thị các tính năng API cần kết nối lại hoặc cần quyền. Lỗi mạng giữ trạng thái trước đó, và phản hồi của token cũ bị bỏ nếu Page đã kết nối lại. Đây là phép kiểm tra nhận diện Page. Việc chuyển lỗi xác thực từ endpoint đăng bài về trạng thái chung của Page thuộc đầu việc tích hợp F5 riêng.
- Nút **Thử làm mới Trang** dùng Facebook User token của phiên SDK hiện tại và chỉ ghi token cho Page được chọn. Retry Page dừng phân trang khi tìm thấy Page, có thể tải song song với refresh tự động và dùng chung cơ chế kiểm tra bản ghi hiện tại khi lưu. Retry Page không gọi SDK làm mới User token.
- Log chẩn đoán có từng giai đoạn, số Page cập nhật và số Page lỗi. Tham chiếu tài khoản là mã có salt ngẫu nhiên theo tiến trình, chỉ dùng để đối chiếu các lỗi trong cùng phiên app.

## Điều hướng

Một Activity (`MainActivity`) + Navigation Component, bottom navigation có 5 tab:
Tạo bài · Bài viết · Bình luận · Số liệu · Cài đặt. Các tab hiện dùng `PlaceholderFragment`.
Khi làm tính năng, thay `android:name` trong `res/navigation/nav_graph.xml` bằng Fragment thật (giữ nguyên `android:id`).
