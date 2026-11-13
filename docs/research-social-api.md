# Nghiên cứu API Facebook Page & LinkedIn (F1, F5, F6, F7)

> **Tài liệu thuộc Issue #5**: `[Research] API Facebook Page và LinkedIn cho F1/F5/F6/F7`  
> **Chủ sở hữu:** Mảng B (Mạng xã hội — `@dhminh678`)  
> **Milestone:** Milestone 1  
> **Mục tiêu:** Nắm vững luồng xác thực OAuth 2.0 (PKCE) trên Android, quyền hạn, endpoint, giới hạn rate limit/App Review, giải pháp dự phòng và kiến trúc tích hợp cho `core/social/SocialChannelClient`.

## 0. Phạm vi và trạng thái quyết định

Tài liệu này là kết quả nghiên cứu, không phải cam kết rằng mọi endpoint hoặc quyền trong bảng dưới đây
đã được Meta/LinkedIn cấp cho ứng dụng `SocialAgent`. Các nội dung có nhãn **Chưa xác minh**, **Cần kiểm
thử thực tế** hoặc **Đề xuất** không được dùng làm căn cứ duy nhất để triển khai production.

### Quyết định áp dụng cho Milestone 1

1. **Không nhúng `client_secret` hoặc `app_secret` vào APK.** Client Android chỉ giữ các định danh công
   khai. Token nhạy cảm được lưu qua **`SecureTokenStore`** (DataStore Preferences + Android Keystore AES-GCM; thay thế hoàn toàn `EncryptedSharedPreferences` đã bị Google deprecate, B1). Mọi lệnh gọi API gửi token qua header `Authorization: Bearer ...`, tuyệt đối không truyền trong query string URL và che giấu qua `redactHeader` trong Interceptor (B2). Tuân thủ Quy tắc 5 của `AGENTS.md`.
2. **Facebook:** Pages API hiện ưu tiên Facebook Login for Business cho app quản lý Page; Meta Android
   SDK có thể được dùng cho trải nghiệm đăng nhập native nếu cấu hình sản phẩm/Dashboard phù hợp (bổ sung `facebook_client_token` và đăng ký cả debug lẫn release key hash, B3).
   Web OAuth qua BFF chỉ là phương án dự phòng và chưa thuộc phạm vi Android app hiện tại; không tự
   giả định rằng PKCE thủ công có thể đổi token mà không cần secret.
3. **LinkedIn — Tách thành 2 app độc lập:** Theo quy định chính thức của LinkedIn ([LinkedIn API Partner Support Guide](https://learn.microsoft.com/en-us/linkedin/shared/linkedin-api-partner-support-guide), [LinkedIn Community Management Overview](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/community-management-overview)), *Community Management API* bắt buộc phải là sản phẩm duy nhất trên một developer app (nút xin sẽ bị khóa nếu app đã tích hợp sản phẩm khác). Do đó, nhóm tách thành 2 app LinkedIn riêng biệt: (1) App **Member** (`LINKEDIN_MEMBER_CLIENT_ID`) bật OIDC và *Share on LinkedIn* (scope `openid profile w_member_social`) cho F1 cá nhân và đăng bài cá nhân; (2) App **Org** (`LINKEDIN_ORG_CLIENT_ID`) chỉ bật duy nhất *Community Management API* cho F1 Org, F5/F6/F7 trên Company Page. Đối với app Org (không có OIDC/openid), URN người dùng quản trị được lấy trực tiếp từ trường `roleAssignee` trong response của `GET /rest/organizationAcls` thay vì gọi `/v2/userinfo`.
   > **Cập nhật theo kết quả TEST-06 (thử thực tế trên App Member):** `scope=openid profile w_member_social` ở `/oauth/native-pkce/authorization` bị LinkedIn từ chối với thông báo *"Open ID permission is not supported for PKCE flows"*. Thiếu `openid` thì không gọi được `/v2/userinfo` để lấy mã thành viên (`sub`) làm author, và `r_liteprofile` bị cấm. **Nhóm chuyển App Member sang Luồng B (Standard OAuth 2.0 qua BFF) và không xin bật Native PKCE nữa.** Hợp đồng BFF đã triển khai: `POST /auth/linkedin/start`, `GET /auth/linkedin/callback`, `POST /auth/linkedin/token` (xem Luồng B bên dưới; mã nguồn ở `backend/linkedin-bff/`). App Org vẫn chưa triển khai; khi làm sẽ dùng cùng BFF với `LINKEDIN_ORG_CLIENT_ID`.
4. **BFF LinkedIn tối giản.** Chỉ gồm 3 endpoint đăng nhập (`/auth/linkedin/start`, `/callback`, `/token`), không lưu token lâu dài,
   không chuyển tiếp `refresh_token`, không có `bff_session_token`/`refresh`/`logout`. Mã nguồn tạm đặt ở `backend/linkedin-bff/`
   và nên chuyển sang repository backend riêng. Việc lưu `refresh_token` phía server và chính sách logout server (nếu cần sau này)
   vẫn phải thiết kế trong issue backend riêng. **Điều kiện trước khi dùng thật:** access token đi qua bộ nhớ BFF tối đa 60 giây nên
   cần nhóm ghi nhận ngoại lệ Quy tắc 5 của `AGENTS.md` qua issue nhãn `contract` (xem cảnh báo ở phần Vòng đời Token).
5. **Không để `LinkedInClient` tự truy vấn Room DB để lấy URN bài viết gốc khi Reply (B12):** Tầng Repository chịu trách nhiệm đọc `Comment.remotePostId` từ Room và truyền xuống client; client mạng không phụ thuộc ngược vào DB. Đề xuất mở issue `contract` để bổ sung hàm overload `reply(comment: Comment, text: String)` trong `SocialChannelClient` (tuân thủ Quy tắc 1 của `AGENTS.md`).
6. **Hàng đợi đăng bài chống trùng lặp (Idempotency Protocol):** Cả Facebook và LinkedIn đều không hỗ trợ native idempotency header. Việc chỉ lưu `idempotencyKey` đơn thuần trong Room không bảo vệ được kịch bản server đã nhận bài nhưng client bị timeout, rớt mạng hoặc process bị kill trước khi ghi `remotePostId`. Do đó, hàng đợi WorkManager bắt buộc áp dụng giao thức: ghi trạng thái `PUBLISHING` kèm `attemptStartedAt` vào Room **trước** khi gọi API; phân loại lỗi mạng để chỉ retry khi chắc chắn chưa gửi được dữ liệu; nếu gặp timeout/không rõ kết quả thì phải **đối soát trước qua feed** (Facebook `/{page-id}/feed?since=...`, LinkedIn `GET /rest/posts?author=...`) trước khi được phép đăng lại.
7. **Đánh giá rủi ro xét duyệt Community Management API cho đồ án sinh viên (B13):** Do Community Management API yêu cầu xét duyệt đơn đăng ký về quyền sở hữu Page doanh nghiệp, đây là rủi ro lớn nhất của dự án. **Hạn quyết định (Decision Deadline): Hết Tuần 1 của Milestone 1**. Nếu LinkedIn không phê duyệt CM API cho App Org của nhóm, toàn bộ nhánh LinkedIn Organization sẽ kích hoạt phương án dự phòng: F5 chuyển sang luồng `HANDED_OFF` (thông báo + đăng thủ công qua Share Sheet), F6/F7 dùng `FakeSocialChannelClient` cho demo để không làm trễ tiến độ đồ án.

### Phân loại độ tin cậy

| Nhãn | Cách sử dụng |
|---|---|
| **Đã đối chiếu tài liệu chính thức** | Có thể dùng làm cơ sở thiết kế, nhưng vẫn cần kiểm thử integration trước khi phát hành. |
| **Chưa xác minh (cần thử trên Portal/API thực tế)** | Chỉ dùng để lập kế hoạch test; không hard-code hành vi thành công. |
| **Đề xuất/quan sát** | Không xem là giới hạn hoặc capability chính thức cho đến khi có nguồn xác nhận. |

---

## 1. Tổng quan & So sánh khả năng (Channel Capabilities)

Đối chiếu trực tiếp với enum `ChannelCapability` trong `core/model/Channel.kt`:
```kotlin
enum class ChannelCapability { PUBLISH, READ_COMMENTS, REPLY, MENTION, METRICS }
```

### Bảng ma trận khả năng của từng kênh (Capability Matrix)

> *Ghi chú về định nghĩa:* `ChannelCapability` bao gồm 5 giá trị: `PUBLISH`, `READ_COMMENTS`, `REPLY`, `MENTION`, `METRICS`. Số lượng người theo dõi (`followersCount`) là trường dữ liệu được gán kèm trong thông tin tài khoản hoặc truy vấn qua `METRICS`, không phải một hằng số enum riêng biệt.

| Khả năng (`ChannelCapability`) | Facebook Page (Meta Graph API, ví dụ `v26.0`) | LinkedIn Organization (Company Page) | LinkedIn Member (Trang cá nhân) |
|---|---|---|---|
| **`PUBLISH` (Đăng bài)** | ✅ **Có** (Text, link qua `/{page-id}/feed` [Meta Pages API - Posts](https://developers.facebook.com/documentation/pages-api/posts); ảnh đơn qua `/{page-id}/photos` và đa ảnh qua `attached_media` trên feed theo [Meta Graph API - Page Photos](https://developers.facebook.com/docs/graph-api/reference/page/photos/)) | ✅ **Có điều kiện** (Text, link, ảnh đơn; `multiImage` chỉ dùng khi product/quyền của app hỗ trợ [LinkedIn MultiImage API](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/shares/multiimage-post-api), giới hạn phải đối chiếu tài liệu version hiện hành) | ✅ **Có điều kiện** (Text, link, ảnh đơn khi app được cấp scope/product đăng bài phù hợp; không coi việc bật sản phẩm là tự động cấp quyền cho mọi app [LinkedIn Posts API](https://learn.microsoft.com/en-us/linkedin/marketing/integrations/community-management/shares/posts-api)) |
| **`READ_COMMENTS` (Đọc comment)** | ✅ **Có** (Comment cấp 1, reply đa cấp qua `filter=stream` hoặc trường `order` [Meta Graph API - Object Comments](https://developers.facebook.com/docs/graph-api/reference/object/comments), cấu trúc phân trang nhận về qua con trỏ `cursors` trong response [Meta Pages API - Comments & Mentions](https://developers.facebook.com/docs/pages-api/comments-mentions/)) | ✅ **Có điều kiện** (Đọc comment và reaction khi product, scope và role của app được cấp [LinkedIn Comments API](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/shares/comments-api)) | ⚠️ **Bị hạn chế / Cần kiểm tra Portal** (Tài liệu Comments và Social Metadata quy định quyền đọc comment cá nhân `r_member_social_feed` chỉ cấp cho developer được chọn lọc — *granted to select developers only* [LinkedIn Comments API](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/shares/comments-api), [LinkedIn Social Metadata API](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/shares/social-metadata-api); nếu app không được cấp thì kích hoạt `HANDED_OFF`) |
| **`REPLY` (Trả lời comment)** | ✅ **Có** (Gửi reply comment dưới tư cách Page [Meta Graph API - Object Comments](https://developers.facebook.com/docs/graph-api/reference/object/comments), [Meta Pages API - Comments & Mentions](https://developers.facebook.com/docs/pages-api/comments-mentions/)) | ✅ **Có điều kiện** (Gửi reply khi product, scope và role của app được cấp [LinkedIn Comments API](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/shares/comments-api)) | ⚠️ **Bị hạn chế / Cần kiểm tra Portal** (Tài liệu Network Update Social Actions và Comments liệt kê scope `w_member_social_feed` cho hành động tương tác cá nhân [LinkedIn Network Update Social Actions](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/shares/network-update-social-actions), [LinkedIn Comments API](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/shares/comments-api); cần kiểm tra trên Developer Portal xem app có được cấp không, nếu không thì kích hoạt `HANDED_OFF`) |
| **`MENTION` (Nhắc tên)** | ⚠️ **Chưa coi là capability mặc định** (Tài liệu Comments & Mentions [Meta Pages API - Comments & Mentions](https://developers.facebook.com/docs/pages-api/comments-mentions/) chỉ hướng dẫn mention trong comment/reply khi Page có feature "Page Mentioning", bật "Others Tagging this Page" và có PSID của người đã tương tác; tài liệu Posts [Meta Pages API - Posts](https://developers.facebook.com/documentation/pages-api/posts) không hướng dẫn tag tự do khi đăng feed $\rightarrow$ Fallback về `HANDED_OFF`) | ⚠️ **Khả thi qua Little Text Format nếu có URN** (Hỗ trợ cú pháp chính thức `@[Tên](urn:li:organization:...)` hoặc `urn:li:person:...` trong `commentary` bài viết [LinkedIn Little Text Format](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/shares/little-text-format), nhưng app không có API tìm kiếm URN tự do; nếu không có URN thì fallback về `HANDED_OFF`) | ⚠️ **Khả thi qua Little Text Format nếu có URN** (Cần URN người/tổ chức cụ thể [LinkedIn Little Text Format](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/shares/little-text-format); nếu không có URN thì fallback về `HANDED_OFF`) |
| **`METRICS` (Số liệu)** | ✅ **Có** (Reactions, Comments, Shares, và metric Insights được version hiện hành hỗ trợ [Meta Deprecated Page Insights Metrics](https://developers.facebook.com/documentation/pages-api/platforminsights/page/deprecated-metrics/), [Meta Graph API Changelog v25.0](https://developers.facebook.com/docs/graph-api/changelog/version25.0)) | ✅ **Có điều kiện** (Organization Share Statistics và follower statistics khi product, scope và role của app được cấp [LinkedIn Organization Share Statistics](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/organizations/share-statistics), [LinkedIn Organization Follower Statistics](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/organizations/follower-statistics)) | ⚠️ **Bị hạn chế / Cần kiểm tra Portal** (Community Management API hỗ trợ `r_member_postAnalytics` để đọc tương tác bài viết Member, nhưng cần đăng ký xét duyệt sản phẩm [LinkedIn Increasing Access](https://learn.microsoft.com/en-us/linkedin/marketing/increasing-access?view=li-lms-2026-10); nếu chưa được cấp thì kích hoạt `HANDED_OFF`) |
| **Số follower (`followersCount`)** *(Dữ liệu kèm `METRICS`)* | ⚠️ Dùng field `followers_count` nếu field và quyền được version hiện hành cho phép; không dùng metric Insights `page_fans` đã deprecated | ⚠️ `firstDegreeSize` qua `networkSizes` khi endpoint và product hiện hành cho phép (lưu ý tài liệu `networkSizes` không yêu cầu `rw_organization_admin` hay vai trò `ADMINISTRATOR` như endpoint Follower Statistics) | ❌ **Không hỗ trợ mặc định** (Không suy ra follower cá nhân từ số kết nối; chỉ dùng endpoint/member permission nếu LinkedIn cấp rõ ràng) |
| **Idempotency native** | ❌ Không hỗ trợ native (Giao thức chống trùng qua Room: ghi `PUBLISHING` trước khi gọi API, đối soát qua feed nếu timeout/không rõ kết quả trước khi retry) | ❌ **Không hỗ trợ native** (Giao thức chống trùng qua Room: ghi `PUBLISHING` trước khi gọi API, đối soát qua `/rest/posts` nếu timeout/không rõ kết quả trước khi retry) | ❌ **Không hỗ trợ native** (Giao thức chống trùng qua Room tương tự cột Org) |
| **Tập hợp gán cho `ChannelAccount.capabilities` (F1)** | Gán theo Page Tasks thực tế của tài khoản (Theo tài liệu Posts API [Meta Pages API - Posts](https://developers.facebook.com/documentation/pages-api/posts), bài feed yêu cầu các Page task `CREATE_CONTENT`, `MANAGE` và `MODERATE` — tài liệu dùng liên từ "and", cần kiểm thử xem có chấp nhận riêng task `CREATE_CONTENT` không; riêng ảnh yêu cầu cụ thể task `CREATE_CONTENT` [Meta Graph API - Page Photos](https://developers.facebook.com/docs/graph-api/reference/page/photos/); `MODERATE` cho `READ_COMMENTS` và `MODERATE` + `CREATE_CONTENT` cho `REPLY` [Meta Pages API - Comments & Mentions](https://developers.facebook.com/docs/pages-api/comments-mentions/); `ANALYZE` cho `METRICS` [Meta Graph API - Page Insights](https://developers.facebook.com/docs/graph-api/reference/page/insights/)); chỉ thêm `MENTION` sau khi Page feature/task và endpoint được xác nhận [Meta Pages API - Overview](https://developers.facebook.com/documentation/pages-api/overview), [Meta Graph API - Page Insights](https://developers.facebook.com/docs/graph-api/reference/page/insights/) | Chỉ gán các capability khi token có scope, product (ví dụ Community Management API cho `READ_COMMENTS`/`REPLY`, `rw_organization_admin` — cần kiểm thử kèm `r_organization_social` [TEST-05] — cho `METRICS`) và role tương ứng [LinkedIn Comments API](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/shares/comments-api), [LinkedIn Organization Share Statistics](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/organizations/share-statistics); chỉ thêm `MENTION` sau khi product/version xác nhận | Chỉ thêm `PUBLISH` sau khi token có scope/product đăng bài thực tế [LinkedIn Posts API](https://learn.microsoft.com/en-us/linkedin/marketing/integrations/community-management/shares/posts-api); không thêm `READ_COMMENTS`, `REPLY`, `METRICS` hoặc `MENTION` nếu chưa được cấp và kiểm thử |

---

## 2. Kênh Facebook Page (Meta Graph API)

> [!IMPORTANT]
> **Chính sách Phiên bản & Vòng đời Meta Graph API (Version Sunset Policy):**
> - **Chu kỳ phát hành & Vòng đời (Theo bảng Versions [Meta Graph API Changelog](https://developers.facebook.com/docs/graph-api/changelog)):** Theo bảng danh sách phiên bản của Meta, các phiên bản Graph API thường có khoảng cách ra mắt vài tháng (ví dụ: v24.0 ra mắt 08/10/2025, v25.0 ra mắt 18/02/2026, v26.0 ra mắt 29/07/2026). Thời hạn hỗ trợ có sự phân hóa theo từng sản phẩm: Graph API cốt lõi thường kéo dài ~28-29 tháng (ví dụ: v24.0 hết hạn 18/02/2028 ~ 28 tháng; v25.0 hết hạn 29/07/2028 ~ 29 tháng), nhưng các API chuyên biệt như Marketing API phiên bản v24.0 chỉ có thời hạn hỗ trợ **12 tháng** (08/10/2025 đến 06/10/2026 theo [Meta Graph API Changelog](https://developers.facebook.com/docs/graph-api/changelog)). Meta không công bố một cam kết cố định về chu kỳ hay thời gian hỗ trợ tối thiểu chung cho mọi loại API; ngày sunset cụ thể phải luôn được xác nhận trên bảng versioning/changelog hiện hành.
> - **Tình trạng version cũ:** Không hard-code ngày sunset của một version trong tài liệu triển khai; trước khi gọi API phải kiểm tra changelog/versioning chính thức của Meta vì version cũ có thể bị từ chối hoặc không còn được hỗ trợ.
> - **Phiên bản áp dụng chuẩn hóa:** Tài liệu dùng `v26.0` (phát hành ngày 29/07/2026) làm ví dụ kiểm thử tại thời điểm cập nhật. Trước khi triển khai, phải xác nhận version đang Active và ngày sunset trên changelog/versioning chính thức của Meta.
> - **Thay đổi kỹ thuật quan trọng & Metric Deprecation:**
>   - **Gỡ bỏ metric hàng loạt:** Meta đã deprecated nhiều metric Insights cho mọi Graph API version. Theo tài liệu chính thức:
>     - Các metric reach và impressions truyền thống đã bị gỡ bỏ hoàn toàn trên các version hiện hành: `post_impressions`, `post_impressions_unique`, `page_impressions`, `page_impressions_unique`, cùng nhóm `page_fans`. *(Lưu ý: Các nguồn tài liệu chính thức của Meta từng nêu mốc thời gian mâu thuẫn nhau giữa tháng 06/2025 và tháng 06/2026, tuy nhiên tại thời điểm hiện tại thì trên mọi version hiện hành các metric này đều đã bị deprecate hoàn toàn).*
>     - **Deprecate bổ sung trên v26.0 (theo Changelog v25.0 [Meta Graph API Changelog v25.0](https://developers.facebook.com/docs/graph-api/changelog/version25.0)):** Meta công bố khi `v26.0` phát hành, các metric reach và impressions cụ thể sau bị **gỡ bỏ hoàn toàn cho mọi version**:
>       - Nhóm Page: `page_impressions_unique`, `page_impressions_paid_unique`, `page_impressions_viral_unique`, `page_impressions_nonviral_unique`, `page_posts_impressions`, `page_posts_impressions_unique`, `page_posts_impressions_paid`, `page_posts_impressions_paid_unique`, `page_posts_impressions_organic_unique`, `page_posts_served_impressions_organic_unique`, `page_posts_impressions_viral`, `page_posts_impressions_viral_unique`, `page_posts_impressions_nonviral`, `page_posts_impressions_nonviral_unique`, `page_video_views_unique`.
>       - Nhóm Post: `post_impressions_unique`, `post_impressions_paid_unique`, `post_impressions_fan_unique`, `post_impressions_organic_unique`, `post_impressions_viral_unique`, `post_impressions_nonviral_unique`, `post_video_views_organic_unique`, `post_video_views_paid_unique`, `post_video_views_unique`.
>       - Nhóm Video: `total_video_impressions`, `total_video_impressions_unique`, `total_video_impressions_paid_unique`, `total_video_impressions_paid`, `total_video_impressions_organic_unique`, `total_video_impressions_organic`, `total_video_impressions_viral_unique`, `total_video_impressions_viral`, `total_video_impressions_fan_unique`, `total_video_impressions_fan`, `total_video_impressions_fan_paid_unique`, `total_video_impressions_fan_paid`, `total_video_views_organic_unique`, `total_video_views_paid_unique`, `total_video_views_unique`.
>       - Nhóm Story: `PAGE_STORY_IMPRESSIONS_BY_STORY_ID`, `PAGE_STORY_IMPRESSIONS_BY_STORY_ID_UNIQUE`.
>   - **Danh sách metric TUYỆT ĐỐI TRÁNH DÙNG (tên metric cụ thể theo Changelog v25.0 [Meta Graph API Changelog v25.0](https://developers.facebook.com/docs/graph-api/changelog/version25.0)):** Toàn bộ các metric liệt kê ở trên cùng nhóm `page_impressions`, `post_impressions`, `page_fans` (theo Changelog v25.0 [Meta Graph API Changelog v25.0](https://developers.facebook.com/docs/graph-api/changelog/version25.0), các metric này sẽ bị gỡ bỏ và trả về lỗi trên mọi version; mã lỗi thường gặp theo [Meta Graph API - Page Insights](https://developers.facebook.com/docs/graph-api/reference/page/insights/) khi gọi metric không hợp lệ là *invalid metric error* mã `100`).
>   - **Metric thay thế chính thức (Alternatives):**
>     - Lượt xem/hiển thị bài viết: dùng **`post_media_view`** (hoặc `post_total_media_view_unique` cho lượt xem duy nhất).
>     - Lượt xem Page: `page_media_view` (hoặc `page_total_media_view_unique`).
>     - Số lượng follower/fan: dùng field `followers_count` của Page node hoặc metric `page_follows`.
>     - Các tương tác phản hồi: `comments.summary(true)` (trả về `summary.total_count` theo [Meta Graph API - Object Comments](https://developers.facebook.com/docs/graph-api/reference/object/comments)); trường `shares` và `reactions.summary(total_count)` được đối chiếu trực tiếp từ tài liệu [Meta Graph API - Page Post Reference](https://developers.facebook.com/docs/graph-api/reference/post/).
> - **Quy ước triển khai:** Trong mã nguồn Kotlin của Mảng B, version này cần được định nghĩa thành hằng số tập trung (ví dụ: `private const val FACEBOOK_GRAPH_API_VERSION = "v26.0"`) để dễ bảo trì và nâng cấp định kỳ.

### 2.1. Luồng OAuth 2.0 trên Android & Quản lý Token (Kiến trúc Public Client)

- **Chuẩn kiến trúc:** Tuân thủ RFC 8252 (OAuth 2.0 for Native Apps) — ứng dụng Android là **Public Client**, **tuyệt đối không nhúng `client_secret` (App Secret)** vào app hay APK.
- **Trạng thái xác minh của luồng Custom Tabs + PKCE thủ công:**
  > [!WARNING]
  > **Trạng thái: Chưa xác minh (cần thử trên Portal/API thực tế)**
  > - Theo tài liệu chính thức của Meta về [Manual Login Flow](https://developers.facebook.com/documentation/facebook-login/guides/advanced/manual-flow), endpoint đổi token `GET /oauth/access_token` tiêu chuẩn yêu cầu bắt buộc phải có `client_secret` bên cạnh `code`.
  > - Mặc dù thư viện `AppAuth-Android` hỗ trợ PKCE ở phía client, tài liệu Graph API hiện hành chưa xác nhận chính thức việc chấp nhận bỏ qua `client_secret` chỉ bằng `code_verifier` cho ứng dụng di động thông thường mà không có backend hỗ trợ.
  > - Do đó, nhóm phân tích rõ hai phương án triển khai khả thi dưới đây:

#### Phương án 1 (Native SDK; phải khớp cấu hình sản phẩm): Sử dụng Meta Android SDK (`com.facebook.android:facebook-login`)
Đây là lựa chọn triển khai native của app. Tuy nhiên, tài liệu Pages API hiện ưu tiên
Facebook Login for Business cho app quản lý Page; việc SDK có thể dùng được hay không vẫn phụ thuộc
product, configuration, permission và version mà Dashboard cấp, không tự suy ra chỉ từ việc thêm SDK:
- **Cơ chế xác thực không Secret:** Meta SDK không sử dụng `client_secret`. Thay vào đó, Meta xác thực danh tính ứng dụng thông qua **App ID + Package Name (`com.lttbdd.socialagent`) + Android Key Hash (SHA-1 fingerprint)** đã đăng ký trên Meta App Dashboard.
- **Trải nghiệm đăng nhập (UX):** Tự động kích hoạt App Switch (mở trực tiếp app Facebook đã cài sẵn trên máy) hoặc mở Chrome Custom Tabs nếu máy chưa cài app, giúp người dùng đăng nhập 1 chạm.
- **Tự động quản lý vòng đời Token (Chưa xác minh (cần thử trên Portal/API thực tế)):**
- Theo tài liệu [Access Tokens](https://developers.facebook.com/documentation/facebook-login/guides/access-tokens), long-lived User Access Token thường có thời hạn khoảng **60 ngày**, nhưng lifetime thực tế có thể thay đổi hoặc hết hạn sớm. Facebook SDK quản lý một phần vòng đời token; app vẫn phải đọc trạng thái thực tế, xử lý token bị thu hồi/hết hạn và yêu cầu người dùng đăng nhập/cấp quyền lại khi cần.

#### Phương án 2 — Tài liệu thiết kế tham khảo cho Backend/BFF (Ngoài phạm vi Milestone 1)

> Phần này chỉ mô tả phương án dự phòng cho một repository backend/BFF tương lai.
> Không triển khai phương án này trong Milestone 1 của ứng dụng Android.

##### Luồng Web OAuth (AppAuth / Custom Tabs) kèm BFF

Nếu sau Milestone 1 nhóm xây dựng một backend/BFF riêng và không nhúng Facebook SDK:

> [!WARNING]
> **Trạng thái xác minh của Redirect URI đối với Meta Web OAuth:**
> - **Luồng HTTPS Callback qua BFF (thiết kế tham khảo):** Tuân thủ tài liệu chính thức về [Manual Login Flow](https://developers.facebook.com/documentation/facebook-login/guides/advanced/manual-flow), `redirect_uri` phải nằm trong danh sách *Valid OAuth Redirect URIs* (sử dụng giao thức chuẩn `https://`). Meta chuyển hướng về endpoint HTTPS của máy chủ backend/BFF, sau đó BFF chuyển hướng an toàn về Android App Links.
> - **Luồng Custom Scheme trực tiếp (`com.lttbdd.socialagent://...`):** ⚠️ **Chưa xác minh (cần thử trên Portal/API thực tế)**. Tài liệu Meta chưa xác nhận việc hỗ trợ đăng ký Custom Scheme trong danh sách *Valid OAuth Redirect URIs* cho Web Dialog (theo quan sát trên Dashboard, mục này yêu cầu URL HTTPS; custom scheme `fb<APP_ID>://authorize` chỉ được Meta Android SDK sử dụng trong cơ chế App Switch nội bộ).

##### Quy trình tham khảo cho một backend/BFF tương lai:

1. **Khởi tạo ủy quyền & Đăng ký phiên với BFF (App-to-BFF PKCE):**
   - **Tạo bí mật chống chặn bắt Deep Link:** Trước khi mở trình duyệt, Android app sinh chuỗi ngẫu nhiên bảo mật `state` và cặp khóa PKCE nội bộ: `app_code_verifier` (chuỗi ngẫu nhiên bí mật có độ entropy cao, chỉ lưu trong bộ nhớ RAM của app) và `app_code_challenge = BASE64URL(SHA256(app_code_verifier))`.
   - **Khởi tạo phiên trên BFF qua endpoint `POST /auth/facebook/start`:**
     - Để server BFF biết lưu trữ và theo dõi `state` trước khi nhận callback từ Meta, app gửi request khởi tạo phiên:
     ```http
     POST https://api.yourdomain.com/auth/facebook/start
     Content-Type: application/json

     {
       "state": "{secure-random-state}",
       "code_challenge": "{app_code_challenge}"
     }
     ```
     - Server BFF lưu trữ cặp `(state, app_code_challenge)` vào bộ nhớ cache tạm thời (ví dụ Redis / memory store với TTL ngắn ~5–10 phút) để đối chiếu khi nhận callback.
   - **Mở Custom Tabs ủy quyền:** Sau khi phiên được khởi tạo trên BFF, App mở Android Custom Tabs điều hướng tới Meta Dialog OAuth, sử dụng **HTTPS Redirect URI** trỏ về máy chủ BFF:

   *Trường hợp A — Facebook Login cổ điển (Truyền danh sách `scope`):*
   ```http
   GET https://www.facebook.com/v26.0/dialog/oauth?
       client_id={app-id}
       &redirect_uri=https://api.yourdomain.com/auth/facebook/callback
       &response_type=code
       &scope=public_profile,pages_show_list,pages_manage_posts,pages_read_engagement,pages_manage_engagement,pages_read_user_content,read_insights
       &state={secure-random-state}
   ```
   *(Lưu ý về danh sách scope — B6: Hướng dẫn Posts/Comments của Meta có chỗ nhắc tới `pages_read_user_engagement`, nhưng từ điển quyền chuẩn [Meta Permissions Reference](https://developers.facebook.com/docs/permissions) không tồn tại scope này mà chỉ có `pages_read_user_content`. Bộ scope chuẩn được chốt bao gồm: `public_profile`, `pages_show_list`, `pages_manage_posts`, `pages_read_engagement`, `pages_manage_engagement`, `pages_read_user_content`, `read_insights` (thêm `business_management` nếu Page thuộc Business Portfolio). Tuyệt đối không đưa scope không tồn tại vào request đăng nhập SDK để tránh lỗi xác thực).*
   `read_insights` chỉ được thêm nếu App Dashboard và endpoint/version Insights thực tế của ứng dụng
   yêu cầu quyền này; không chép nguyên một bộ scope vào production khi chưa kiểm tra trạng thái cấp quyền.

   *Trường hợp B — Facebook Login for Business (Truyền tham số `config_id`):*
   Theo tài liệu chính thức về [Facebook Login for Business](https://developers.facebook.com/docs/facebook-login/facebook-login-for-business/), đối với App loại `Business`, các quyền được cấu hình sẵn trong mục *Configurations* trên Dashboard tạo thành một Configuration ID (`config_id`). Khi đó, request thay thế toàn bộ tham số `scope` bằng `config_id`:
   ```http
   GET https://www.facebook.com/v26.0/dialog/oauth?
       client_id={app-id}
       &redirect_uri=https://api.yourdomain.com/auth/facebook/callback
       &response_type=code
       &config_id={facebook-config-id}
       &state={secure-random-state}
   ```

   > ⚠️ **Lưu ý kiểm tra Dashboard:** Nhóm cần kiểm tra trực tiếp trên Meta App Dashboard xem sản phẩm kích hoạt là **Facebook Login cổ điển** hay **Facebook Login for Business**:
   > - Ví dụ `scope=...` là cú pháp truyền quyền tiêu chuẩn trong Facebook Login cổ điển.
   > - Với Facebook Login for Business, tài liệu chính thức [Meta Facebook Login for Business](https://developers.facebook.com/docs/facebook-login/facebook-login-for-business/) khuyến nghị tạo Configuration trên Dashboard và truyền tham số `config_id` thay cho danh sách `scope` (lưu ý: tài liệu Meta xác nhận `config_id` là tham số tùy chọn và `scope` vẫn có thể được truyền kèm, nhưng Meta khuyến cáo không nên dùng chung). Nếu sử dụng Meta Android SDK, truyền Configuration ID theo cơ chế cấu hình của SDK; chi tiết tên hàm cụ thể cần đối chiếu Javadoc thực tế khi cài đặt thư viện.

2. **Xử lý Callback trên BFF & Chuyển hướng an toàn qua Android App Links:**
   - Meta chuyển hướng trình duyệt về endpoint BFF: `https://api.yourdomain.com/auth/facebook/callback?code={authorization-code}&state={returned-state}`.
   - Server BFF đối chiếu `returned-state` với dữ liệu trong cache (đã lưu ở bước `POST /auth/facebook/start`). Nếu `state` không tồn tại hoặc đã hết hạn, BFF từ chối request ngay lập tức để chống CSRF.
   - Server BFF giữ `client_secret` an toàn trong biến môi trường, lập tức gọi Meta Graph API để đổi `code` lấy User Access Token và nâng cấp thành Long-lived Token qua `grant_type=fb_exchange_token`.
   - **Lưu trữ tạm thời trong cache và sinh mã trao đổi (In-Memory Cache with Short TTL):**
     - Sau khi nhận Long-lived Token từ Meta, BFF lưu trữ tạm thời token này trong bộ nhớ cache tốc độ cao (in-memory store / Redis) với thời gian sống cực ngắn (TTL $\le 60$ giây, khớp với thời hạn của `exchange_code`), gắn liền với `app_code_challenge` của phiên để chờ app gọi lấy.
     - BFF tạo một **mã trao đổi dùng một lần** (**One-Time Exchange Code** / `exchange_code`, thời hạn hiệu lực $\le 60$ giây) gắn liền với bản ghi cache đó.
   - **Chống lộ Token qua URL & Phòng vệ chặn bắt Deep Link (Interception Defense):**
     - BFF **tuyệt đối không truyền access token trong URL**.
     - **Ưu tiên hàng đầu (Lựa chọn chính):** BFF chuyển hướng trình duyệt về ứng dụng Android thông qua **Android App Links (HTTPS)**:
       ```
       https://socialagent.app/oauth/facebook/callback?exchange_code={one-time-exchange-code}&state={secure-random-state}
       ```
       *(Android App Links sử dụng file chứng thực Digital Asset Links `/.well-known/assetlinks.json` khớp với chữ ký số APK để hệ điều hành ưu tiên mở thẳng app chính chủ, giúp **giảm đáng kể rủi ro** bị ứng dụng khác nghe lén URL; tuy nhiên hành vi này vẫn phụ thuộc vào phiên bản Android và trạng thái xác minh domain thành công trên thiết bị).*
     - **Lựa chọn dự phòng (Fallback):** Deep Link Custom Scheme `com.lttbdd.socialagent://oauth/facebook/callback?...` (khi domain chưa kịp xác minh hoặc trên môi trường dev/thiết bị chưa hỗ trợ đầy đủ App Links).
     - **Lớp bảo mật bắt buộc ở cả hai lựa chọn (App-to-BFF PKCE):** Cơ chế **PKCE nội bộ (`app_code_verifier` / `app_code_challenge`) là lớp phòng thủ cốt lõi bắt buộc ở CẢ HAI lựa chọn chuyển hướng** (chứ không chỉ là phương án dự phòng). Dù callback đi qua App Links hay Custom Scheme, nếu URL bị can thiệp hay lộ `exchange_code`, kẻ tấn công vẫn hoàn toàn không thể đổi lấy token vì thiếu `app_code_verifier` (chỉ lưu trong RAM của app thật).

3. **App đổi mã nhận Token qua kênh Back-Channel kèm `code_verifier` (Proof-of-Possession):**
   - Ứng dụng Android bắt App Links callback, kiểm tra đối chiếu `state`.
   - App gửi request HTTPS trực tiếp (`POST`) từ repository tới BFF, đính kèm `code_verifier` nội bộ đã lưu trong RAM:
     ```http
     POST https://api.yourdomain.com/auth/facebook/token
     Content-Type: application/json

     {
       "exchange_code": "{one-time-exchange-code}",
       "state": "{secure-random-state}",
       "code_verifier": "{app_code_verifier}"
     }
     ```
   - **Xác thực kép tại BFF:** Server BFF xác thực `exchange_code`, đồng thời băm `SHA256(code_verifier)` và so sánh với `app_code_challenge`. Nếu không khớp (kẻ xấu bắt trộm `exchange_code` trên deep link nhưng không có `code_verifier` trong RAM), BFF từ chối yêu cầu ngay lập tức!
   - Nếu hợp lệ: Server hủy `exchange_code` ngay lập tức (one-time use), **xóa sạch ngay lập tức Long-lived Token khỏi bộ nhớ cache tạm thời**, sinh token phiên nội bộ `bff_session_token` (định danh phiên thiết bị an toàn giữa App và BFF), và trả về payload trong JSON response body qua kênh HTTPS:
     ```json
     {
       "access_token": "{user-long-lived-token}",
       "token_type": "bearer",
       "expires_in": 5183944,
       "bff_session_token": "{bff_session_token}"
     }
     ```
     > *Ghi chú về `expires_in`:* Giá trị `5183944` (~60 ngày) chỉ là số minh họa trong response, không phải giá trị cố định được Meta bảo đảm. Máy chủ BFF bắt buộc phải **chuyển tiếp nguyên vẹn trường `expires_in` thực tế** nhận được từ Meta về cho App, và App client lưu `expiresAt = System.currentTimeMillis() + (response.expires_in * 1000L)` (tuyệt đối không gán cứng hằng số 60 ngày).
   - **Cơ chế cấp & vai trò của `bff_session_token` phía BFF (BFF không lưu token Facebook trong database):**
     - **Nguyên tắc lưu trữ trên BFF (Stateless OAuth Proxy):** Khác với LinkedIn (có thể cần lưu `refresh_token` trong DB server khi product/tier cho phép), Facebook không có cơ chế refresh token giống LinkedIn. Tuy nhiên, Page Access Token vẫn có thể mất hiệu lực khi User Token bị thu hồi, quyền bị gỡ hoặc trạng thái tài khoản/Page thay đổi; không được coi là vĩnh viễn. Trong luồng đăng nhập, **BFF chỉ lưu trữ tạm thời token trong bộ nhớ cache tốc độ cao với TTL cực ngắn ($\le 60$ giây, cùng thời hạn với `exchange_code`) gắn liền với `app_code_challenge` để chờ app client gọi đổi mã, và xóa sạch ngay lập tức sau khi đổi thành công**. **Máy chủ BFF tuyệt đối KHÔNG lưu giữ bất kỳ token Facebook nào trong cơ sở dữ liệu lâu dài (persistent database) của server**. Việc quản lý và lưu trữ token thuộc toàn quyền của app client trong `SecureTokenStore` (kiến trúc DataStore Preferences + Android Keystore AES-GCM; thay thế hoàn toàn `EncryptedSharedPreferences` đã bị Google deprecate, chi tiết tại §5.3).
     - **Cách cấp:** BFF sinh mã phiên ngẫu nhiên độ entropy cao (hoặc JWT ký bởi secret server) và gửi trực tiếp cho App trong response body của endpoint `POST /auth/facebook/token` (kênh HTTPS back-channel bảo mật, tuyệt đối không gửi qua URL/Deep Link).
     - **Vai trò:** Định danh phiên kết nối giữa App và BFF. Dùng làm token xác thực (qua header `Authorization: Bearer {bff_session_token}`) để **quản lý và thu hồi phiên (Session Revocation / Logout)**: khi người dùng bấm "Ngắt kết nối" (Disconnect) trên ứng dụng, app gửi request `POST /auth/facebook/logout` kèm `bff_session_token` để BFF vô hiệu hóa phiên làm việc trên server và dọn sạch session cache. Ngoài ra, token này giúp xác thực định danh app khi gọi các dịch vụ backend nội bộ khác của dự án.
   - **Lưu trữ an toàn phía Client (Bao gồm Page Access Token):**
     - **Chỉ lưu Token trong `SecureTokenStore` (Android Keystore AES-GCM + DataStore):**
       - User Long-lived Token (`access_token`) và mã phiên `bff_session_token`.
       - **Page Access Token của từng Facebook Page:** Đối với ứng dụng Social Agent (quản lý các Facebook Page do người dùng sở hữu), toàn bộ tác vụ cốt lõi F5 (đăng bài), F6 (đọc/trả lời comment) và F7 (lấy số liệu insights) đều thực hiện dưới danh nghĩa Page và sử dụng **Page Access Token** lấy từ `GET /v26.0/me/accounts`. (Lưu ý: Tài liệu Meta [Meta Pages API - Posts](https://developers.facebook.com/documentation/pages-api/posts) có trường hợp ngoại lệ cho phép dùng User Access Token kèm tính năng `Page Public Content Access` nếu app user không sở hữu/quản lý Trang; tuy nhiên trong nghiệp vụ của app ta, Page Access Token là token tiêu chuẩn bắt buộc). Các Page Access Token này là credential nhạy cảm, **chỉ được lưu trữ trong `SecureTokenStore`** và được tra cứu theo `page_id` (ví dụ key: `fb_page_token_{page_id}`).
     - **Room Database tuyệt đối KHÔNG lưu plaintext token:** Room chỉ lưu trữ metadata phi nhạy cảm của tài khoản và từng Page (`account_id` / `page_id`, `name`, `avatar_url`, `category`, `tasks`, `expires_at`, `connection_status`, `capabilities`). Khi Repository thực hiện đăng bài hoặc đọc số liệu cho một Page, app sẽ đọc `account_id` từ Room DB rồi truy xuất Page Access Token tương ứng từ `SecureTokenStore`.
     - **Xử lý khi "Ngắt kết nối" (Disconnect) ở F1:** Khi người dùng bấm ngắt kết nối một Page hoặc đăng xuất tài khoản Facebook, ứng dụng phải **xóa sạch Page Access Token tương ứng** (hoặc toàn bộ User Token và các Page Token liên quan) khỏi `SecureTokenStore`, đồng thời cập nhật/xóa metadata trong Room DB.

##### Luồng PKCE trực tiếp với Custom Scheme (⚠️ Chưa xác minh (cần thử trên Portal/API thực tế)):
Nếu Meta hỗ trợ cấu hình custom scheme và bỏ qua secret qua `code_verifier`:
- Request: `redirect_uri=com.lttbdd.socialagent://oauth/facebook` kèm `code_challenge`.
- Trao đổi: `GET https://graph.facebook.com/v26.0/oauth/access_token` kèm `code_verifier`.
*(Lưu ý: Luồng này không khuyến nghị cho bản release vì thiếu tài liệu xác nhận chính thức từ Meta và theo quan sát thực tế mục Valid OAuth Redirect URIs trên Dashboard yêu cầu URL HTTPS).*

#### So sánh Vòng đời Token giữa 2 Phương án (Token Lifecycle):

**1. Đối với Phương án 1 (Meta Android SDK — SDK hỗ trợ một phần vòng đời phía Client):**
```
[User Login qua Meta SDK (App Switch / Custom Tabs)]
       │
       ▼ (SDK tự xử lý xác thực qua App ID + Key Hash, trả AccessToken object)
[User Access Token (~60 ngày)] (SDK có thể quản lý một phần vòng đời; ứng dụng vẫn phải kiểm tra token và xử lý hết hạn/thu hồi)
       │
       ▼ (Gọi GET /v26.0/me/accounts trực tiếp từ App với User Access Token)
[Page Access Token (Không có expires_at cố định nếu sinh từ User Long-lived Token)]
```
> *Ghi chú:* Meta SDK quản lý một phần vòng đời User Access Token ở phía client, nhưng ứng dụng không
> được giả định SDK luôn gia hạn token hoặc token luôn có TTL cố định. Phải đọc trạng thái/metadata
> thực tế, xử lý hết hạn/thu hồi và kích hoạt cấp quyền lại khi cần. `fb_exchange_token` chỉ dùng
> trong luồng server được tài liệu hỗ trợ.

**2. Đối với Phương án 2 (Web OAuth thủ công qua Custom Tabs + BFF):**
```
[User Login trên Chrome Custom Tabs]
       │
       ▼ (Meta chuyển hướng HTTPS về BFF Server với authorization_code)
[BFF Server nhận code] (BFF đổi code lấy Short-lived Token & gọi fb_exchange_token lấy Long-lived Token)
       │
       ▼ (BFF chuyển hướng về App qua Android App Links với exchange_code một lần & state)
[App nhận exchange_code] (App gọi POST /auth/facebook/token qua HTTPS back-channel để nhận Long-lived Token)
       │
       ▼ (Gọi GET /v26.0/me/accounts với User Long-lived Token)
[Page Access Token (Từ User Long-lived Token; không có expires_at cố định)]
```
> *Ghi chú:* Sơ đồ có bước gọi `fb_exchange_token` **chỉ áp dụng cho Phương án 2**. Do tham số `grant_type=fb_exchange_token` yêu cầu bắt buộc phải có `client_secret`, các bước từ đổi code đến nâng cấp token phải được thực hiện trên máy chủ BFF để đảm bảo bí mật không bị lộ ra file APK.

* **Đặc tính của Page Access Token:** 
  - **Bản chất thời hạn của Page Access Token:** 
    - **Mâu thuẫn trong tài liệu chính thức của Meta về thời hạn Page Token (Chưa xác minh (cần thử trên Portal/API thực tế)):**
      - Trang hướng dẫn [Get a Long-Lived Page Access Token](https://developers.facebook.com/docs/facebook-login/guides/access-tokens/get-long-lived) mô tả: Page Access Token sinh từ User Long-lived Token không có ngày hết hạn cố định (`data.expires_at: 0`, giao diện Debugger hiển thị `Expires: Never`) [Meta Long-Lived Page Access Tokens](https://developers.facebook.com/docs/facebook-login/guides/access-tokens/get-long-lived).
      - Ngược lại, trang tài liệu tổng quan [Pages API Overview](https://developers.facebook.com/documentation/pages-api/overview) lại ghi: Page Access Token là duy nhất cho mỗi Page, user, app và **có thời hạn hiệu lực** (*"and have an expiration time"*) [Meta Pages API - Overview](https://developers.facebook.com/documentation/pages-api/overview).
      - **Kết luận kiến trúc (Chưa xác minh (cần thử trên Portal/API thực tế)):** Do tài liệu Meta tự mâu thuẫn giữa 2 trang này, nhóm không tự suy diễn hạn cố định (không giả định vĩnh viễn cũng không gán cứng 60 ngày), mà bắt buộc kiểm chứng giá trị `expires_at` thực tế bằng công cụ Access Token Debugger / `debug_token` trên Meta Developer Portal trong giai đoạn phát triển [Meta Long-Lived Page Access Tokens](https://developers.facebook.com/docs/facebook-login/guides/access-tokens/get-long-lived), [Meta Pages API - Overview](https://developers.facebook.com/documentation/pages-api/overview).
    - Dù token có ngày hết hạn hay không, Page Access Token **vẫn bị vô hiệu hóa ngay lập tức** trong các điều kiện sau:
      1. Người dùng đổi mật khẩu tài khoản Facebook cá nhân (lỗi mã 190, subcode 460) [Meta Graph API - Error Handling](https://developers.facebook.com/docs/graph-api/guides/error-handling).
      2. Ứng dụng không được cài đặt hoặc người dùng chưa đăng nhập / đã hủy cấp quyền (lỗi mã 190, subcode 458) [Meta Graph API - Error Handling](https://developers.facebook.com/docs/graph-api/guides/error-handling).
      3. Người dùng bị xóa vai trò quản trị viên hoặc mất nhiệm vụ quản lý (Page Tasks tương ứng như `MODERATE`, `CREATE_CONTENT`, `ANALYZE`) trên chính Page đó (subcode 492) [Meta Graph API - Error Handling](https://developers.facebook.com/docs/graph-api/guides/error-handling).
    - **Tác động thiết kế F1:** Ứng dụng không gán TTL cứng 60 ngày cho Page Token trong Room DB, mà quản lý trạng thái token theo mã lỗi thực tế khi gọi API (bắt mã lỗi `190` kèm subcode 458, 460 để chuyển trạng thái `ConnectionStatus.TOKEN_EXPIRED`, hoặc subcode 492 để xử lý như lỗi phân quyền `PermissionError` [Meta Graph API - Error Handling](https://developers.facebook.com/docs/graph-api/guides/error-handling)).
  - **Phương pháp kiểm thử thời hạn Page Token trong giai đoạn Dev (Chỉ dùng khi kiểm thử, không đưa vào App):**
    - **Cách thức kiểm chứng:** Trong quá trình phát triển và kiểm thử, nhóm sử dụng công cụ [Access Token Debugger](https://developers.facebook.com/tools/debug/accesstoken/) trên Meta Developer Portal (hoặc gọi thủ công endpoint `GET /debug_token?input_token={page_access_token}&access_token={app_access_token}` qua Postman hoặc Graph API Explorer).
    - **Kết quả cần tìm:** Đọc các trường metadata thực tế do Access Token Debugger hoặc `/debug_token` trả về, đặc biệt trường `data.expires_at` (giá trị `0` xác nhận token không có ngày hết hạn) và `data.data_access_expires_at` (Unix timestamp chỉ mốc hết hạn truy cập dữ liệu sau 90 ngày nếu người dùng không hoạt động). *(Lưu ý: `data_access_expires_at` là tên trường trong response của `/debug_token`, tương ứng với thuộc tính `dataAccessExpirationTime` của đối tượng `AccessToken` trên Android SDK).*
    - **Phân định phạm vi kiến trúc (Không mâu thuẫn):**
      - Việc kiểm tra qua Access Token Debugger / `GET /debug_token` **chỉ áp dụng độc lập trong giai đoạn kiểm thử nội bộ**, tuyệt đối **không nhúng vào mã nguồn ứng dụng Android**.
      - Khi ứng dụng chạy thực tế (Runtime), app tuân thủ nghiêm ngặt nguyên tắc ở mục dưới đây: **không gọi `/debug_token`**. Với User Access Token, có thể kiểm tra sơ bộ bằng `GET /v26.0/me`; `GET /v26.0/me/accounts` chỉ dùng với User Access Token để lấy Page Access Token, không dùng Page Access Token để suy ra trạng thái User Token.
* **Cơ chế kiểm tra token an toàn & Xử lý lỗi trong App Runtime (F1):**
  - **Lý do ứng dụng client không gọi endpoint `/debug_token` khi chạy thực tế:**
    - Theo tài liệu chính thức của Meta về [Access Tokens Guide](https://developers.facebook.com/docs/facebook-login/guides/access-tokens) (và công cụ [Access Token Debugger](https://developers.facebook.com/tools/debug/accesstoken/)), endpoint `GET /debug_token?input_token={input-token}&access_token={access-token}` đòi hỏi một "valid access token" (thường là App Access Token phía server hoặc một token hợp lệ cùng App ID) để kiểm tra metadata.
    - Việc gọi `/debug_token` trên app di động làm phát sinh thêm một network round-trip thừa và đòi hỏi token kiểm tra phụ không cần thiết cho client.
    - Do đó, ứng dụng có thể kiểm tra sơ bộ User Access Token bằng `GET /v26.0/me` với chính token đang dùng; `GET /v26.0/me/accounts` là request lấy danh sách Page và Page Access Token, chỉ thực hiện với User Access Token. Đây không thay thế việc xử lý `401/403`, thiếu scope hoặc token bị thu hồi khi gọi endpoint nghiệp vụ thực tế.
  - **Xử lý mã lỗi `OAuthException` (Code `190`) & Phân loại theo `error_subcode`:**
    - Theo tài liệu [Meta Graph API Error Handling](https://developers.facebook.com/docs/graph-api/guides/error-handling), Meta **không cam kết cố định HTTP status** (lỗi có thể trả về HTTP 400 Bad Request hoặc 401 Unauthorized tùy ngữ cảnh). Ứng dụng **phải parse và phân loại lỗi dựa trên body JSON** (`error.code` và `error.error_subcode`), không phụ thuộc đơn thuần vào HTTP status code:
      ```json
      {
        "error": {
          "message": "Error validating access token: ...",
          "type": "OAuthException",
          "code": 190,
          "error_subcode": 463,
          "fbtrace_id": "..."
        }
      }
      ```
    - **Phân loại `error_subcode`:** Bảng ánh xạ `error_subcode` dưới đây dùng làm gợi ý xử lý chuẩn cho các tình huống phổ biến, không phải hợp đồng cố định bắt buộc cho mọi endpoint. Tài liệu lỗi Meta không bảo đảm mọi subcode có cùng ý nghĩa qua mọi endpoint/version; client phải kết hợp phân tích `error.code`, `error_subcode`, message, loại token (User hay Page), endpoint đang gọi và tình trạng quyền/Page task:
      - Subcode `463` / `467`: Access token hết hạn hoặc không hợp lệ [Meta Graph API - Error Handling](https://developers.facebook.com/docs/graph-api/guides/error-handling) $\rightarrow$ Đánh dấu `ConnectionStatus.TOKEN_EXPIRED`, thông báo kết nối lại.
      - Subcode `460`: Người dùng đổi mật khẩu tài khoản Facebook cá nhân [Meta Graph API - Error Handling](https://developers.facebook.com/docs/graph-api/guides/error-handling) $\rightarrow$ Vô hiệu hóa token, đánh dấu `ConnectionStatus.TOKEN_EXPIRED`.
      - Subcode `458`: Ứng dụng không được cài đặt hoặc người dùng chưa đăng nhập vào ứng dụng (cần xác thực lại người dùng) [Meta Graph API - Error Handling](https://developers.facebook.com/docs/graph-api/guides/error-handling) $\rightarrow$ Đánh dấu `ConnectionStatus.TOKEN_EXPIRED`.
      - Subcode `492`: Người dùng liên kết với mã truy cập Trang không có vai trò thích hợp trên Trang [Meta Graph API - Error Handling](https://developers.facebook.com/docs/graph-api/guides/error-handling) $\rightarrow$ Xử lý như lỗi phân quyền (`PermissionError`), không retry và hướng dẫn người dùng kiểm tra quyền quản trị trên Page.

  - **Xử lý lỗi phân quyền API & Mã lỗi đặc thù trên Comment API (Meta):**
    - **Ý nghĩa kỹ thuật từ tài liệu chính thức của Meta:**
      - **Mã lỗi `10`** (*Permission Denied*): Ứng dụng hoặc Access Token không có quyền thực hiện thao tác này [Meta Graph API - Error Handling](https://developers.facebook.com/docs/graph-api/guides/error-handling).
      - **Dải mã lỗi phân quyền `200–299`** (*Permissions-related errors*):
        - Mã `200` (*Permissions error*): Lỗi phân quyền truy cập hoặc thực hiện thao tác theo tài liệu tham chiếu Comment API của Meta [Meta Graph API - Error Handling](https://developers.facebook.com/docs/graph-api/guides/error-handling), [Meta Graph API - Page Photos](https://developers.facebook.com/docs/graph-api/reference/page/photos/).
        - Mã `283` (*Requires pages_read_engagement or pages_read_user_content*): Mã lỗi phân quyền yêu cầu quyền tương tác mở rộng [Meta Graph API - Page Photos](https://developers.facebook.com/docs/graph-api/reference/page/photos/), [Meta Graph API - Page Reference](https://developers.facebook.com/docs/graph-api/reference/page); khi thao tác đọc/trả lời comment thiếu quyền tương tác (Cần kiểm thử thực tế trên endpoint `/comments`) $\rightarrow$ Xử lý như lỗi phân quyền không thể tự khắc phục ở runtime (`PermissionError`), không retry vô hạn và kích hoạt cơ chế `HANDED_OFF`.
      - **Mã lỗi đặc thù & giới hạn tần suất trên Pages API:**
        - Mã `1705`: Theo tài liệu chính thức [Pages API: Comments & Mentions](https://developers.facebook.com/docs/pages-api/comments-mentions/), Meta mô tả trường hợp thực hiện bình luận dưới danh nghĩa User cá nhân thay vì Page cũng trả về mã này (message ví dụ của Meta là *"There was an error posting to this wall"*, client không hard-code match theo chuỗi này) [Meta Pages API - Comments & Mentions](https://developers.facebook.com/docs/pages-api/comments-mentions/). Vì đây là lỗi do danh tính actor/token không đúng loại, việc retry tự động với cùng token sẽ không thể khắc phục được. Trước khi phân loại lỗi, client **cần kiểm tra `error.message`, actor/Page token đang dùng (đang dùng User Token hay Page Token) và trạng thái của Page**:
          - Nếu gửi nhầm User Token hoặc thiếu quyền đại diện của Page: Phân loại là lỗi cấu hình/xác thực token (`AuthenticationError`/`ConfigurationError`), tuyệt đối không retry, thông báo người dùng cấp lại Page Access Token hoặc chọn đúng Page quản trị.
          - Nếu do trạng thái Page bị khóa hoặc hạn chế bình luận: Báo lỗi tương ứng để người dùng kiểm tra trạng thái Page trên Meta Business Suite.
        - Mã `80001` (*Page account call rate limit*): Xuất hiện trong tài liệu Pages API (như Page Photos) chỉ sự cố vượt tần suất cuộc gọi cấp độ Page account nói chung (không riêng bình luận) $\rightarrow$ Xử lý như `TransientError`, tạm dừng hàng đợi và retry sau thời gian chờ (exponential backoff) [Meta Graph API - Page Photos](https://developers.facebook.com/docs/graph-api/reference/page/photos/).
        - Mã `506` (*Duplicate post*): Xuất hiện khi ứng dụng cố tình hoặc retry đăng bài viết có nội dung trùng lặp liên tiếp; Graph API từ chối đăng để chống spam ("Không thể đăng bài viết trùng lặp liên tiếp. Hãy thay đổi nội dung của bài viết và thử lại") $\rightarrow$ Xử lý như lỗi nội dung không thể retry tự động (`PermanentError`), thông báo người dùng đổi nội dung hoặc ngăn hàng đợi gửi trùng lặp [Meta Graph API - Error Handling](https://developers.facebook.com/docs/graph-api/guides/error-handling).
    - **Ánh xạ kiến trúc & Cơ chế xử lý:**
      - Đây có thể là lỗi phân quyền không thể tự khắc phục bằng retry hoặc refresh token, nhưng client phải đọc `error.code`, `error_subcode`, message, endpoint, Page task và trạng thái quyền trước khi phân loại. Không tự động coi mọi mã trong dải `200–299` là lỗi vĩnh viễn.
      - Khi xác nhận lỗi là thiếu quyền/Page task hoặc App Review không thể khắc phục ở runtime trong quá trình đăng bài (F5), Client **không retry vô hạn** và kích hoạt cơ chế dự phòng bàn giao 2 pha (xem chi tiết tại §4):
        1. Worker cập nhật trạng thái bài viết sang **`PostStatus.HANDED_OFF`** trong Room Database kèm lý do lỗi phân quyền.
        2. Worker phát **System Notification** thông báo cho người dùng ("Bài viết cần đăng thủ công") với `PendingIntent` mở màn hình chi tiết bài viết.
        3. Tuyệt đối **không** tự động ghi Clipboard hay gọi mở Activity/Share Sheet từ Worker chạy nền (nhằm tuân thủ quy tắc Android 10+ background activity launch restrictions và tránh crash do thiếu `FLAG_ACTIVITY_NEW_TASK`). Việc sao chép nội dung vào Clipboard và kích hoạt Android Share Intent native chỉ được thực hiện ở tầng tiền cảnh (Foreground Fragment) khi người dùng chủ động bấm nút "Đăng thủ công".

### 2.2. Danh sách quyền (Permissions / Scopes)
Đối chiếu trực tiếp với các khả năng trong `ChannelCapability` (`core/model/Channel.kt`):

| Chức năng / Khả năng | Scope cần xin | Mô tả & Lưu ý kỹ thuật |
|---|---|---|
| **F1 (Kết nối kênh)** | `public_profile`, `pages_show_list` | Lấy danh sách các Page người dùng quản lý (`GET /me/accounts`) và lấy Page Access Token [Meta Pages API - Overview](https://developers.facebook.com/documentation/pages-api/overview). `pages_show_list` không có dependencies [Meta Permissions Reference](https://developers.facebook.com/docs/permissions). |
| **`PUBLISH` (Đăng bài)** | `pages_manage_posts` | Đăng bài viết (text, link), upload ảnh/video thay mặt Page lên endpoint `/{page-id}/feed` hoặc `/{page-id}/photos` [Meta Pages API - Posts](https://developers.facebook.com/documentation/pages-api/posts), [Meta Graph API - Page Photos](https://developers.facebook.com/docs/graph-api/reference/page/photos/). Đăng bài không cần `pages_manage_engagement`. **Yêu cầu Page task:** Task `CREATE_CONTENT` trên Page. **Dependencies bắt buộc khi App Review:** `pages_manage_posts` phụ thuộc `pages_read_engagement` và `pages_show_list` [Meta Permissions Reference](https://developers.facebook.com/docs/permissions). |
| **`READ_COMMENTS` (Đọc comment)** | `pages_read_user_content`, `pages_read_engagement` | Đọc danh sách bình luận của người dùng và các phản hồi trên bài viết (`GET /{post-id}/comments?filter=stream` hoặc trường `order`) [Meta Graph API - Object Comments](https://developers.facebook.com/docs/graph-api/reference/object/comments), [Meta Pages API - Comments & Mentions](https://developers.facebook.com/docs/pages-api/comments-mentions/). Dùng `pages_read_user_content` thay cho `pages_read_user_engagement` không tồn tại trong từ điển quyền. **Yêu cầu Page task:** `MODERATE` trên Page. |
| **`REPLY` (Trả lời comment)** | `pages_manage_engagement`, `pages_read_engagement`, `pages_read_user_content` | Gửi bình luận trả lời (`POST /{comment-id}/comments`) dưới danh nghĩa Page [Meta Graph API - Object Comments](https://developers.facebook.com/docs/graph-api/reference/object/comments), [Meta Pages API - Comments & Mentions](https://developers.facebook.com/docs/pages-api/comments-mentions/). **Yêu cầu Page task:** `MODERATE` và `CREATE_CONTENT` trên Page. **Dependencies bắt buộc khi App Review:** `pages_manage_engagement` phụ thuộc `pages_read_user_content` và `pages_show_list` [Meta Permissions Reference](https://developers.facebook.com/docs/permissions). |
| **`MENTION` (Nhắc tên)** | Chỉ xem xét khi endpoint/feature xác nhận; chịu xét duyệt feature **MENTIONING** | Hạn chế theo Pages API Comments & Mentions: Tài liệu chính thức [Meta Pages API - Comments & Mentions](https://developers.facebook.com/docs/pages-api/comments-mentions/) chỉ hướng dẫn mention trong comment/reply khi Page có feature "Page Mentioning", bật "Others Tagging this Page", và chỉ tag được người dùng đã từng tương tác bài viết/comment trên Page; tài liệu Posts [Meta Pages API - Posts](https://developers.facebook.com/documentation/pages-api/posts) không hướng dẫn tag tự do khi đăng feed $\rightarrow$ Fallback về `HANDED_OFF`. |
| **`METRICS` (Số liệu)** | `pages_read_engagement`, `read_insights` *(đối chiếu thêm quyền và metric được yêu cầu bởi endpoint/version cụ thể)* | Đọc số lượt hiển thị/xem (`post_media_view` hoặc metric hiện hành tương ứng), tương tác (`reactions`, `shares`, `comments count`) và số lượng người theo dõi (`followers_count`) [Meta Graph API - Page Insights](https://developers.facebook.com/docs/graph-api/reference/page/insights/). Đọc Page Insights yêu cầu **Page task:** `ANALYZE` trên Page [Meta Graph API - Page Insights](https://developers.facebook.com/docs/graph-api/reference/page/insights/). **Dependencies bắt buộc khi App Review:** `read_insights` phụ thuộc `pages_read_engagement` và `pages_show_list` [Meta Permissions Reference](https://developers.facebook.com/docs/permissions). |

### 2.3. Các Endpoint chính
> **Quy ước triển khai trong repository:** Các ví dụ `FacebookClient`/Repository ở mục này
> chỉ là tên vai trò. Implementation thật dự kiến đặt tại
> `app/src/main/java/com/lttbdd/socialagent/data/social/FacebookClient.kt` (hoặc
> `data/facebook/FacebookClient.kt` nếu nhóm tách package theo nền tảng), được expose qua
> `core/social/SocialChannelClient.kt` và cung cấp bằng Hilt trong `di/`. Không import trực tiếp
> implementation từ feature khác.

1. **Lấy danh sách Page và Page Token (F1):**
   ```http
   GET https://graph.facebook.com/v26.0/me/accounts?fields=id,name,access_token,category,tasks
   ```
   *Lưu ý kỹ thuật với Page thuộc Business Portfolio (⚠️ Chưa xác minh (cần thử trên Portal/API thực tế)):*
   Theo quan sát thực tế khi tích hợp (⚠️ nội dung ngoài tài liệu chính thức [Meta Pages API - Overview](https://developers.facebook.com/documentation/pages-api/overview)), nếu Page được quản lý bởi Meta Business Portfolio / Business Manager và người dùng chỉ được gán nhiệm vụ (*Page Tasks*) qua tài khoản doanh nghiệp thay vì sở hữu trực tiếp cá nhân, endpoint `/me/accounts` có thể không trả về đầy đủ hoặc trả về mảng rỗng `data: []` (điểm này chưa được mô tả trong tài liệu chính thức [Meta Pages API - Overview](https://developers.facebook.com/documentation/pages-api/overview)). Khi đó, cần kiểm tra cấp quyền `business_management` ([Meta Permissions Reference](https://developers.facebook.com/docs/permissions)) hoặc kiểm tra cấu hình tài sản trong Meta Business Suite; nhóm cần kiểm thử thực tế hành vi của `/me/accounts` với tài khoản doanh nghiệp trên môi trường dev.
2. **Đăng bài viết kèm nội dung văn bản (F5):**
   ```http
   POST https://graph.facebook.com/v26.0/{page-id}/feed
   Authorization: Bearer {page-access-token}
   Content-Type: application/json

   {
     "message": "Nội dung bài viết do AI soạn..."
   }
   ```
3. **Đăng bài viết kèm ảnh đơn (F5):**
   ```http
   POST https://graph.facebook.com/v26.0/{page-id}/photos
   Authorization: Bearer {page-access-token}
   Content-Type: multipart/form-data

   caption="Nội dung bài viết kèm ảnh..."
   source=@/path/to/image.png
   ```
   *(Lưu ý: Trên endpoint `/{page-id}/photos`, tham số `caption` là chuẩn; `message` và `name` đã bị đánh dấu Deprecated [Meta Graph API - Page Photos](https://developers.facebook.com/docs/graph-api/reference/page/photos/)).*  
   *Trả về:* `{"id": "photo_id", "post_id": "{page-id}_{post-id}"}`.

4. **Đăng bài viết đa ảnh (Multi-photo Post — F5):**
   - **Bước 1: Tải từng ảnh ở chế độ chưa công khai (`published=false`):**
     ```http
     POST https://graph.facebook.com/v26.0/{page-id}/photos
     Authorization: Bearer {page-access-token}
     Content-Type: multipart/form-data

     published=false
     source=@/path/to/image1.png
     ```
     *Nhận về:* `{"id": "{photo_id_1}"}`. Lặp lại cho từng ảnh để thu thập danh sách `[photo_id_1, photo_id_2, ...]`.
   - **Bước 2: Tạo bài post trên Feed đính kèm mảng `attached_media` (hướng dẫn chi tiết tại [Meta Graph API - Page Photos](https://developers.facebook.com/docs/graph-api/reference/page/photos/)):**
     ```http
     POST https://graph.facebook.com/v26.0/{page-id}/feed
     Authorization: Bearer {page-access-token}
     Content-Type: application/json

     {
       "message": "Nội dung bài viết kèm album ảnh...",
       "attached_media": [
         {"media_fbid": "{photo_id_1}"},
         {"media_fbid": "{photo_id_2}"}
       ]
     }
     ```
     *Nhận về:* `{"id": "{page-id}_{post-id}"}`.
   - *Lưu ý kỹ thuật & Vòng đời ảnh:*
     - **Vòng đời của ảnh chưa công khai (Unpublished Photos TTL):** Theo tài liệu Photo Specifications của Meta, các ảnh tải lên với `published=false` chỉ tồn tại tạm thời và **sẽ bị Meta tự động xóa sau khoảng 24 giờ** nếu không được gắn vào bài post qua `attached_media`. Do đó, hàng đợi WorkManager của ứng dụng phải thực hiện Bước 2 ngay sau khi hoàn tất tải ảnh lên, tránh để ảnh tồn đọng quá 24 giờ gây lỗi invalid media id [Meta Graph API - Page Photos](https://developers.facebook.com/docs/graph-api/reference/page/photos/). Nếu upload ảnh cho bài viết lên lịch (Scheduled Post), tài liệu Meta khuyến nghị gắn thêm tham số `temporary=true` (khi đó `published` bắt buộc là `false` và không được truyền `scheduled_publish_time` trong request upload ảnh [Meta Graph API - Page Photos](https://developers.facebook.com/docs/graph-api/reference/page/photos/)).
     - **Số lượng ảnh và dung lượng file:** Không tải lên quá giới hạn của endpoint/version đang dùng; client phải đối chiếu tài liệu và xử lý rõ lỗi validation hoặc upload failure từ response.
     - Trong Milestone 1, model `PostDraft` ưu tiên hỗ trợ `imageUri` (ảnh đơn), quy trình đa ảnh được chuẩn hóa sẵn sàng cho giai đoạn mở rộng tiếp theo.

   > [!WARNING]
   > **F5 — Giao thức chống đăng trùng lặp (Idempotency) & Xử lý lỗi không rõ kết quả**
   >
   > Meta Graph API **không** có cơ chế idempotency header native. Việc chỉ kiểm tra `idempotencyKey` trong Room DB chỉ đúng khi lần đăng trước **đã ghi nhận kết quả thành công**. Trường hợp nguy hiểm nhất trong thực tế là: request `POST /{page-id}/feed` đã gửi tới máy chủ Meta và bài viết đã được tạo thành công, nhưng client bị timeout, rớt mạng hoặc tiến trình (process) bị hệ điều hành hủy trước khi kịp ghi nhận `remotePostId`. Khi đó, WorkManager sẽ tự động retry, Room DB chưa có `remotePostId` nên sẽ gửi lại request và khiến **bài viết bị đăng 2 lần lên Facebook Page công khai**.
   >
   > *(Lưu ý về mã lỗi 506: Facebook có mã lỗi `506` - Duplicate post nhằm từ chối bài viết có nội dung trùng lặp liên tiếp trong thời gian ngắn, nhưng đây là cơ chế heuristic không đảm bảo và không được Meta cam kết như một giải pháp idempotency chính thức. Ứng dụng tuyệt đối không dựa vào mã lỗi này làm cơ chế chống trùng).*
   >
   > **Giao thức chống đăng trùng bắt buộc cho Worker:**
   > 1. **Trước khi gọi API:** Trong một Room transaction, chuyển trạng thái bài viết sang `PUBLISHING`, cập nhật `attemptStartedAt = System.currentTimeMillis()` và `outcomeUnknown = false`.
   > 2. **Gọi API:** Khi nhận được response thành công, ghi ngay `remotePostId` và chuyển sang trạng thái thành công trong transaction riêng.
   > 3. **Phân loại lỗi mạng khi thất bại:**
   >    - Lỗi chắc chắn **chưa gửi** được request (DNS thất bại `UnknownHostException`, không kết nối được `ConnectException`): cho phép retry theo backoff thông thường.
   >    - Lỗi **không rõ kết quả / có thể đã tới server** (`SocketTimeoutException` sau khi đã gửi body, lỗi khi đọc response stream, process bị hủy giữa chừng): đánh dấu `outcomeUnknown = true`. **Tuyệt đối không gọi publish lại ngay**.
   > 4. **Cơ chế đối soát (Reconciliation) trước khi retry:** Khi Worker chạy lại và gặp bài viết đang ở trạng thái `PUBLISHING` mà chưa có `remotePostId` (hoặc `outcomeUnknown = true`):
   >    - Worker **không được đăng lại ngay**, mà phải gọi đối soát: `GET https://graph.facebook.com/v26.0/{page-id}/feed?since={attemptStartedAt - 60s}&fields=id,message,created_time`.
   >    - So khớp `message` bài đăng và thời gian tạo `created_time`:
   >      - Nếu tìm thấy bài viết đã tồn tại trên feed của Page, trích xuất `id`, cập nhật `remotePostId` vào Room DB và coi như tác vụ đã hoàn thành thành công.
   >      - Chỉ khi đối soát xác nhận bài viết chưa từng xuất hiện trên Page sau khoảng thời gian an toàn thì mới được phép thực hiện gọi publish lại đúng một lần.
   >      - Nếu tiếp tục gặp lỗi trong quá trình đối soát hoặc không thể xác minh, chuyển trạng thái bài viết sang yêu cầu người dùng xác nhận thủ công (`NEEDS_CONFIRMATION` hoặc `HANDED_OFF`), không tự ý đăng lại để tránh spam Page.
   >
   > **Lỗ hổng concurrency của `FakeSocialChannelClient` (cần sửa):**
   > `FakeSocialChannelClient.publish` hiện **chưa an toàn khi hai coroutine gọi song song**:
   > 1. Cả hai miss cache, cùng `delay`, rồi mới ghi map.
   > 2. `remoteId` lấy từ `publishedByKey.size + 1` **trước** khi insert → hai key khác nhau có thể ra cùng `remotePostId` (F6 comment / F7 metrics đụng id).
   > 3. Cùng một key: cả hai vẫn đi qua `delay` như thể đăng hai lần; `getOrPut` chỉ giữ một entry nên unit test gọi **nối tiếp** vẫn xanh.
   > 
   > Khi sửa: Dùng `Mutex` + double-check key trong lock; cấp `remoteId` bằng bộ đếm nguyên tử (`AtomicInteger`, không dùng `size + 1`); bổ sung unit test `async` cùng key và khác key với `latencyMillis > 0`.

5. **Lấy danh sách comment của bài viết (F6):**
   ```http
   GET https://graph.facebook.com/v26.0/{post-id}/comments?filter=stream&fields=id,from,message,created_time,comment_count,parent&limit=50
   Authorization: Bearer {page-access-token}
   ```
   *Lưu ý kỹ thuật về tham số `filter` và cơ chế phân trang Cursor:*
   - **Sử dụng `filter=stream` hoặc trường `order`:** Giá trị filter mặc định của Graph API trên edge `/comments` là `toplevel` (chỉ trả về bình luận cấp 1). Muốn lấy danh sách đầy đủ tất cả các cấp bình luận của bài viết, tài liệu Meta hướng dẫn đặt thông số `filter=stream` hoặc sử dụng trường `order` (`chronological` / `reverse_chronological`) [Meta Graph API - Object Comments](https://developers.facebook.com/docs/graph-api/reference/object/comments).
   - **Phân trang bằng con trỏ (Cursor-based Pagination):** Phản hồi API trả về đối tượng `paging` chứa cặp con trỏ `cursors` (`before` và `after`) theo cấu trúc response mẫu trong tài liệu [Meta Pages API - Comments & Mentions](https://developers.facebook.com/docs/pages-api/comments-mentions/):
     ```json
     "paging": {
       "cursors": {
         "before": "QVFIU...",
         "after": "QVFIU..."
       },
       "next": "https://graph.facebook.com/v26.0/{post-id}/comments?..."
     }
     ```
     Để tải trang kế tiếp trong thực tế, client truyền tham số `after={paging.cursors.after}` (lưu ý: tham số query `limit` và `after` là quy ước phân trang cursor phổ biến của Graph API, cần kiểm thử thực tế trên endpoint bài post).
6. **Trả lời comment (F6):**
   ```http
   POST https://graph.facebook.com/v26.0/{comment-id}/comments
   Authorization: Bearer {page-access-token}
   Content-Type: application/json

   {
     "message": "Chào bạn, cảm ơn bạn đã quan tâm..."
   }
   ```
   *(Lưu ý về URL endpoint — Chưa xác minh (cần thử trên Portal/API thực tế): Theo tài liệu [Meta Graph API - Object Comments](https://developers.facebook.com/docs/graph-api/reference/object/comments), endpoint trả lời comment là `POST /{comment-id}/comments`; tuy nhiên hướng dẫn [Meta Pages API - Comments & Mentions](https://developers.facebook.com/docs/pages-api/comments-mentions/) lại có ví dụ curl gửi trực tiếp tới `POST /{comment-id}`. Nhóm cần kiểm thử thực tế trên Graph API Explorer để xác nhận endpoint chuẩn [Meta Graph API - Object Comments](https://developers.facebook.com/docs/graph-api/reference/object/comments), [Meta Pages API - Comments & Mentions](https://developers.facebook.com/docs/pages-api/comments-mentions/)).*
7. **Lấy số liệu bài viết (F7):**
   ```http
   GET https://graph.facebook.com/v26.0/{post-id}?fields=shares,comments.summary(true),reactions.summary(total_count)
   Authorization: Bearer {page-access-token}

   GET https://graph.facebook.com/v26.0/{post-id}/insights?metric=post_media_view
   Authorization: Bearer {page-access-token}
   ```
   *Lưu ý thay đổi trường dữ liệu quan trọng:*
   - Meta đã deprecated `post_impressions` và cung cấp `post_media_view` làm alternative; `post_impressions_unique` có alternative là `post_total_media_view_unique` [Meta Graph API Changelog v25.0](https://developers.facebook.com/docs/graph-api/changelog/version25.0), [Meta Deprecated Page Insights Metrics](https://developers.facebook.com/documentation/pages-api/platforminsights/page/deprecated-metrics/). Ngoài ra, theo Changelog v25.0, khi v26.0 phát hành thì toàn bộ các metric reach/impressions sau bị gỡ bỏ hoàn toàn cho mọi version [Meta Graph API Changelog v25.0](https://developers.facebook.com/docs/graph-api/changelog/version25.0):
     - Nhóm Page: `page_impressions_unique`, `page_impressions_paid_unique`, `page_impressions_viral_unique`, `page_impressions_nonviral_unique`, `page_posts_impressions`, `page_posts_impressions_unique`, `page_posts_impressions_paid`, `page_posts_impressions_paid_unique`, `page_posts_impressions_organic_unique`, `page_posts_served_impressions_organic_unique`, `page_posts_impressions_viral`, `page_posts_impressions_viral_unique`, `page_posts_impressions_nonviral`, `page_posts_impressions_nonviral_unique`, `page_video_views_unique`.
     - Nhóm Post: `post_impressions_unique`, `post_impressions_paid_unique`, `post_impressions_fan_unique`, `post_impressions_organic_unique`, `post_impressions_viral_unique`, `post_impressions_nonviral_unique`, `post_video_views_organic_unique`, `post_video_views_paid_unique`, `post_video_views_unique`.
     - Nhóm Video: `total_video_impressions` cùng các biến thể `_unique`, `_paid`, `_paid_unique`, `_organic`, `_organic_unique`, `_viral`, `_viral_unique`, `_fan*`, `total_video_views*`.
     - Nhóm Story: `PAGE_STORY_IMPRESSIONS_BY_STORY_ID`, `PAGE_STORY_IMPRESSIONS_BY_STORY_ID_UNIQUE`.
   - **Bẫy dữ liệu rỗng và mã lỗi:** Nếu Access Token thiếu quyền `read_insights`, API không trả về lỗi HTTP 403 mà trả về tập dữ liệu rỗng `data: []` [Meta Graph API - Page Insights](https://developers.facebook.com/docs/graph-api/reference/page/insights/). Nếu request chứa metric không còn được version hiện hành hỗ trợ, API sẽ trả về lỗi mã `100` (`"(#100) The value must be a valid insights metric"`); nếu không truyền tham số metric, API trả về lỗi mã `3001` (subcode `1504028`) [Meta Graph API - Page Insights](https://developers.facebook.com/docs/graph-api/reference/page/insights/).
   - Để lấy số lượt hiển thị/xem, ưu tiên metric được tài liệu chính thức xác nhận cho version đang dùng, hiện là **`post_media_view`** cho lượt xem không duy nhất [Meta Deprecated Page Insights Metrics](https://developers.facebook.com/documentation/pages-api/platforminsights/page/deprecated-metrics/).
   - Các tương tác comment được lấy qua `comments.summary(true)` (trả về đối tượng `summary.total_count` theo tài liệu edge `/comments` [Meta Graph API - Object Comments](https://developers.facebook.com/docs/graph-api/reference/object/comments)). Đối với số lượt share (`shares`) và số lượt reactions (`reactions.summary(total_count)` hoặc `reactions.summary(true)`), các trường này đã được đặc tả chính thức tại trang reference [Meta Graph API - Page Post Reference](https://developers.facebook.com/docs/graph-api/reference/post/) (trường `shares` trả về đối tượng `{"count": int}`).
8. **Lấy số follower của Page (F7):**
   ```http
   GET https://graph.facebook.com/v26.0/{page-id}?fields=followers_count
   Authorization: Bearer {page-access-token}
   ```
   *Lưu ý về các trường số liệu trên Graph API v26.0 (Page Followers):*
   - **`followers_count`:** Field chuẩn của Page node được tài liệu Graph API sử dụng để đọc tổng số người theo dõi.
   - **Đặc tính của Page dùng New Page Experience (NPE):** Trường `fan_count` biểu thị số người thích Page (đọc qua `Page Public Content Access` hoặc `Page Public Metadata Access`), trong khi `followers_count` là số người theo dõi [Meta Graph API - Page Reference](https://developers.facebook.com/docs/graph-api/reference/page). Đối với Page đã chuyển sang New Page Experience (NPE), tài liệu Meta nêu rõ `fan_count` sẽ trả về giá trị của `followers_count` [Meta Graph API - Page Reference](https://developers.facebook.com/docs/graph-api/reference/page). Tuy nhiên, để đảm bảo tính nhất quán trên mọi phiên bản và trải nghiệm Page, client nên truy vấn trực tiếp trường chuẩn `fields=followers_count` [Meta Graph API - Page Reference](https://developers.facebook.com/docs/graph-api/reference/page).
   - **Metric `page_fans`:** Là metric Insights đã bị deprecated hoàn toàn; không dùng làm nguồn dữ liệu [Meta Deprecated Page Insights Metrics](https://developers.facebook.com/documentation/pages-api/platforminsights/page/deprecated-metrics/).

### 2.4. Giới hạn & Quy định kiểm duyệt (App Review)
- **Giới hạn nội bộ của app:** `63,206` ký tự theo cấu hình `ChannelType.FACEBOOK(maxChars = 63_206)`. Lưu ý: Tài liệu tham chiếu chính thức [Meta Pages API - Posts](https://developers.facebook.com/documentation/pages-api/posts) không công bố con số giới hạn ký tự tối đa cụ thể của bài viết; con số `63,206` hoàn toàn là cấu hình quy ước nội bộ của app, client phải chuẩn bị xử lý lỗi validation từ server nếu endpoint/version thực tế áp dụng giới hạn khác.
- **Kích thước ảnh & Định dạng (Photo Specifications theo Meta Graph API):**
  - **Định dạng file hỗ trợ chính thức:** Meta Pages Photos API chính thức hỗ trợ các định dạng file ảnh: **JPEG (`.jpg`, `.jpeg`), BMP (`.bmp`), PNG (`.png`), GIF (`.gif`), TIFF (`.tiff`)** [Meta Graph API - Page Photos](https://developers.facebook.com/docs/graph-api/reference/page/photos/).
  - **Giới hạn dung lượng tải lên:** Theo tài liệu Photo Specifications của Meta, dung lượng file ảnh không được vượt quá **4 MB**. Riêng đối với file định dạng PNG, file **không được vượt quá 1 MB**, nếu không hình ảnh có thể bị bể hình (*pixelated*) [Meta Graph API - Page Photos](https://developers.facebook.com/docs/graph-api/reference/page/photos/). Client Android cần kiểm tra kích thước file cục bộ trước khi upload multipart, nén hoặc cảnh báo người dùng nếu ảnh vượt quá các ngưỡng này.
- **Rate Limit (Giới hạn tần suất gọi):** Áp dụng cơ chế **Business Use Case (BUC) Rate Limiting** cho Page access token và **Platform Rate Limiting** cho user/app token [Meta Graph API - Rate Limiting](https://developers.facebook.com/docs/graph-api/overview/rate-limiting/):
  - **Bản chất giới hạn động (Dynamic Threshold):** Theo tài liệu chính thức của Meta về [Graph API Rate Limiting](https://developers.facebook.com/docs/graph-api/overview/rate-limiting/), không có hạn ngạch cuộc gọi cố định cho tất cả các Page. Các yêu cầu bằng Page access token tuân theo Giới hạn tốc độ BUC; số liệu thống kê được trả về trong header của phản hồi hoặc hiển thị trên App Dashboard [Meta Graph API - Rate Limiting](https://developers.facebook.com/docs/graph-api/overview/rate-limiting/). Các chi tiết về cửa sổ trượt theo Page Engaged Users và cấu trúc header `X-Business-Use-Case-Usage` / `X-App-Usage` (chứa `call_count`, `cpu_time`, `estimated_time_to_regain_access`) là thông tin ghi nhận từ quan sát thực tế ngoài tài liệu chính thức của Meta (tài liệu tổng quan [Meta Graph API - Rate Limiting](https://developers.facebook.com/docs/graph-api/overview/rate-limiting/) chỉ giải thích nguyên lý phân biệt giữa Platform Rate Limit và BUC Rate Limit mà không đặc tả chi tiết cấu trúc header; Chưa xác minh (cần thử trên Portal/API thực tế) trên response thật).
  - **Xử lý khi chạm trần:** Có thể nhận HTTP 403/429 hoặc lỗi Graph API kèm mã và header usage tùy endpoint. Client phải đọc body lỗi cùng header usage; chỉ map sang `PublishResult.RetryableError` khi response cho thấy lỗi tạm thời hoặc có thời gian retry, không ánh xạ cố định mọi mã lỗi vào retry [Meta Graph API - Rate Limiting](https://developers.facebook.com/docs/graph-api/overview/rate-limiting/), [Meta Graph API - Error Handling](https://developers.facebook.com/docs/graph-api/guides/error-handling).
- **Chính sách hết hạn quyền truy cập dữ liệu (90-Day Data Access Expiration):**
  - **Bản chất chính sách bảo mật nền tảng (Platform Policy):** Theo tài liệu chính thức về [Authentication Versus Data Access](https://developers.facebook.com/documentation/facebook-login/auth-vs-data), Meta áp dụng chính sách thời hạn truy cập dữ liệu người dùng là **90 ngày tính từ lần hoạt động cuối cùng của người dùng** trên ứng dụng [Meta Authentication Versus Data Access](https://developers.facebook.com/documentation/facebook-login/auth-vs-data).
  - **Phân biệt giữa Xác thực (Authentication), Data Access Expiration và Permission Expiration:**
    - Khi hết hạn 90 ngày, phiên đăng nhập của người dùng **vẫn tồn tại** (người dùng vẫn ở trạng thái authenticated, access token không tự động mất hiệu lực xác thực danh tính), nhưng ứng dụng **bị chặn hoàn toàn quyền đọc/truy cập dữ liệu người dùng** cho đến khi được người dùng cấp lại quyền [Meta Authentication Versus Data Access](https://developers.facebook.com/documentation/facebook-login/auth-vs-data).
    - Đây là **Data Access Expiration** của từng người dùng, không đồng nghĩa bản thân mọi permission
      đều có cùng một TTL. Khi data access hết hạn, app phải xin lại quyền/truy cập theo luồng Meta
      hiện hành; quyền cũng có thể bị thu hồi, thay đổi trạng thái xét duyệt hoặc bị từ chối theo
      endpoint/product. Không suy luận trạng thái hết hạn riêng của `pages_read_user_engagement` nếu
      tài liệu permission/version hiện hành không liệt kê permission đó [Meta Authentication Versus Data Access](https://developers.facebook.com/documentation/facebook-login/auth-vs-data).
    - *Lưu ý về mã lỗi:* Tài liệu chính thức của Meta không nêu cụ thể mã lỗi HTTP hay subcode (⚠️ *Trạng thái mã lỗi: Chưa xác minh trong tài liệu chính thức; không khẳng định cố định lỗi OAuthException code 190*).
  - **Cơ chế phát hiện chính thức (Official Detection):**
    - **Phía Android SDK (Client-side):** Kiểm tra trực tiếp trên đối tượng `AccessToken` (`AccessToken.getCurrentAccessToken()`):
      - Thuộc tính `dataAccessExpirationTime`: Thời điểm hết hạn truy cập dữ liệu (`Date`).
      - Hàm `isDataAccessExpired`: Trả về `true` nếu quyền truy cập dữ liệu đã hết hạn sau 90 ngày không hoạt động.
    - **Phía Server / Dev inspection:** Đọc trường `data_access_expires_at` (Unix timestamp) từ endpoint `GET /debug_token?input_token={token}&access_token={app-token}`.
  - **Cơ chế cấp lại quyền chính thức (Re-authorization):**
    - Khi `isDataAccessExpired` là `true` hoặc cuộc gọi truy cập dữ liệu bị từ chối, ứng dụng kích hoạt luồng cấp lại quyền thông qua hàm chính thức của Meta SDK:
      ```kotlin
      LoginManager.getInstance().reauthorizeDataAccess(activity)
      ```
      [Meta Authentication Versus Data Access](https://developers.facebook.com/documentation/facebook-login/auth-vs-data).
    - Meta có thể yêu cầu người dùng xác nhận lại consent hoặc đăng nhập lại; ứng dụng không được giả định rằng mọi lần cấp lại quyền đều chỉ cần một lần bấm.
  - **Lưu ý về Permission Expiration:**
    - Meta vẫn có thể thu hồi, thay đổi trạng thái xét duyệt hoặc từ chối permission theo endpoint/product; không được suy luận quyền đang hợp lệ chỉ từ việc đã từng được App Review phê duyệt. Lưu ý rằng theo tài liệu Authentication Versus Data Access, các quyền của Page như `pages_manage_posts`, `pages_manage_engagement`, `pages_read_engagement` nằm trong danh mục không tự động hết hạn theo thời gian người dùng không hoạt động (*permissions do not expire*) [Meta Authentication Versus Data Access](https://developers.facebook.com/documentation/facebook-login/auth-vs-data). Tuy nhiên, theo quy định bảo mật tại mục Permission Expiration của tài liệu này [Meta Authentication Versus Data Access](https://developers.facebook.com/documentation/facebook-login/auth-vs-data#permission-expiration), **nếu ứng dụng không sử dụng một permission trong vòng 90 ngày liên tục, permission đó vẫn có thể hết hạn (`that permission may expire`)**, bất kể người dùng có hoạt động hay quyền đó đã từng qua App Review.
    - *Tác động đến đồ án:* Cần kiểm tra permission và metric thực tế trên App Dashboard cũng như phản hồi API; không đồng nhất việc người dùng không hoạt động 90 ngày với việc permission cấp độ ứng dụng hết hạn.
- **Quyền nào cần App Review (Meta App Review):**
  - *Standard Access (Dành cho tài khoản có App Role / Business Role):* Các quyền như `public_profile` và `pages_show_list` có thể hoạt động ngay với tài khoản Tester/Developer trong App Roles mà chưa cần App Review hoàn tất [Meta Pages API - Overview](https://developers.facebook.com/documentation/pages-api/overview).
  - *Advanced Access (BẮT BUỘC App Review nếu dùng công chúng):* Khi ứng dụng cần phục vụ các tài khoản hoặc tài sản doanh nghiệp không thuộc quyền quản lý trực tiếp của nhóm phát triển, ứng dụng phải qua App Review để đạt mức Advanced Access [Meta Facebook Login for Business](https://developers.facebook.com/docs/facebook-login/facebook-login-for-business/), [Meta Pages API - Overview](https://developers.facebook.com/documentation/pages-api/overview). Ngoài ra trong Facebook Login for Business, `public_profile` không được yêu cầu riêng lẻ mà bắt buộc đi kèm ít nhất một quyền doanh nghiệp khác [Meta Facebook Login for Business](https://developers.facebook.com/docs/facebook-login/facebook-login-for-business/). Các quyền phục vụ tính năng chính cần được xét theo product và endpoint: `pages_show_list`, `pages_manage_posts`, `pages_read_engagement`, `read_insights`, `pages_read_user_content` (chỉ khi dùng endpoint tương ứng) và `pages_manage_engagement`; cần xác minh quyền và trạng thái xét duyệt trên App Dashboard [Meta Permissions Reference](https://developers.facebook.com/docs/permissions).
  - *Điều kiện qua App Review:* Hồ sơ phải đáp ứng các yêu cầu mà Meta hiển thị cho từng permission/product; có thể bao gồm **Business Verification**, video screencast và các bước xác minh doanh nghiệp [Meta Permissions Reference](https://developers.facebook.com/docs/permissions). Không suy ra rằng mọi permission Pages đều có cùng một bộ điều kiện.
- **Cơ chế phân quyền của Business App (Standard Access vs Advanced Access):**
  - **Bản chất kiểm soát quyền (Không có Development/Live Mode):** Theo tài liệu chính thức [Pages API Overview](https://developers.facebook.com/documentation/pages-api/overview), ứng dụng loại Doanh nghiệp (Business App) **không có App Mode (không có toggle Development Mode hay Live Mode)** như ứng dụng Consumer truyền thống. Thay vào đó, quyền truy cập API được kiểm soát thông qua hai cấp độ:
    1. **Standard Access (Mặc định cho quyền chưa qua review):** Quyền và tính năng API chỉ hoạt động với các tài khoản:
       - Có vai trò chính thức trong mục *App Roles* trên Meta Dashboard (*Administrators*, *Developers*, hoặc *Testers*).
       - Hoặc có vai trò thành viên trong chính *Tài khoản Doanh nghiệp (Meta Business Account)* sở hữu ứng dụng đó.
       - Người dùng bên ngoài (không có vai trò trong App hoặc Business) sẽ bị Meta từ chối truy cập khi app ở mức Standard Access.
    2. **Ràng buộc vai trò trên Page (Page Tasks):** Tài khoản thử nghiệm phải nắm vai trò quản trị hoặc nhiệm vụ phân quyền cụ thể (*Page Tasks*) trên chính Facebook Page được thử nghiệm:
       - **Đăng bài (F5):** Tài liệu hướng dẫn Posts [Meta Pages API - Posts](https://developers.facebook.com/documentation/pages-api/posts) nêu yêu cầu các Page task `CREATE_CONTENT`, `MANAGE` và `MODERATE` trên Page (nguyên văn dùng liên từ "and"; cần kiểm thử thực tế trên môi trường dev nếu tài khoản chỉ có riêng task `CREATE_CONTENT`).
       - **Đọc comment (F6):** Yêu cầu Page task `MODERATE` trên Page.
       - **Trả lời comment (F6):** Yêu cầu Page task `MODERATE` và `CREATE_CONTENT` trên Page (nhiệm vụ gắn với Page quản trị, không có khái niệm task trên comment ID).
       - **Đọc số liệu Page Insights (F7):** Yêu cầu Page task `ANALYZE` trên Page kết hợp quyền `read_insights` và `pages_read_engagement`.
    3. **Advanced Access (Dành cho người dùng công chúng):** Bắt buộc phải hoàn tất quy trình Meta App Review và Business Verification để mọi người dùng đều có thể sử dụng.
    4. **Đánh giá phạm vi đồ án:** Với phạm vi đồ án (F1, F5, F6, F7 trên Page thử nghiệm của nhóm), cấp độ **Standard Access** kết hợp App Roles của các thành viên đáp ứng hoàn toàn đầy đủ yêu cầu phát triển và chấm bài mà chưa cần mở công chúng.

---

## 3. Kênh LinkedIn (LinkedIn REST API)

### 3.1. Luồng OAuth 2.0 trên Android & Quản lý Token

> [!IMPORTANT]
> **Điểm cốt lõi về Native PKCE của LinkedIn:**
> LinkedIn phân tách rõ ràng giữa **giao thức Native PKCE dành riêng cho ứng dụng di động** và **luồng Standard OAuth 2.0 Web**. Nhóm cần nắm rõ điều kiện áp dụng của từng luồng để tránh lỗi phân quyền `Not enough permissions to access Native PKCE protocol` khi tích hợp.

#### Luồng A: LinkedIn Native PKCE Protocol (Trực tiếp Client — Không giữ Secret)
Đây là giao thức chính thức của LinkedIn dành cho các ứng dụng native (Android/Desktop) không có
backend; đối chiếu [OAuth 2.0 for Native Clients](https://learn.microsoft.com/en-us/linkedin/shared/authentication/authorization-code-flow-native):
- **Authorization Endpoint:** `https://www.linkedin.com/oauth/native-pkce/authorization` *(khác với `/oauth/v2/authorization`)*
- **Token Endpoint:** `https://www.linkedin.com/oauth/v2/accessToken`
- **Redirect URI:** Bắt buộc dùng **Loopback Interface** (ví dụ: `http://127.0.0.1:{port}`,
  `http://127.0.0.1:{port}/callback` hoặc IPv6 loopback `http://[::1]:{port}`). Theo tài liệu
  Native PKCE, LinkedIn chỉ giao tiếp với loopback IP; app phải mở HTTP listener trên cổng ngẫu nhiên.
  App phải dùng trình duyệt mặc định của hệ điều hành để thực hiện authorization, không dùng WebView
  nhúng. Việc dùng Custom Tabs, cách giữ listener sống qua vòng đời Android và có cần service hay không
  là quyết định triển khai của app, không phải yêu cầu riêng được LinkedIn bảo đảm.
- **Điều kiện áp dụng bắt buộc:**
  - Mặc định các App tạo trên LinkedIn Developers **chưa được bật cờ Native PKCE**.
  - **Bắt buộc:** LinkedIn phải bật PKCE OAuth 2 flow cho App ID. Nhóm cần liên hệ đầu mối LinkedIn được chỉ định cho ứng dụng trước khi triển khai; không giả định app mới được bật protocol mặc định.
  - ⚠️ **Rủi ro kỹ thuật về Scope OpenID Connect với Native PKCE (Phương án giải quyết & Kiểm thử thực tế [TEST-06] — B4):** Theo tài liệu Sign In with LinkedIn v2 [LinkedIn Sign In with LinkedIn using OpenID Connect](https://learn.microsoft.com/en-us/linkedin/consumer/integrations/self-serve/sign-in-with-linkedin-v2), chuẩn OpenID Connect hiện đại sử dụng các scope: `openid`, `profile`, `email` và endpoint lấy profile là `GET /v2/userinfo`. Tuy nhiên, tài liệu Native PKCE [LinkedIn OAuth 2.0 Native Clients (PKCE)](https://learn.microsoft.com/en-us/linkedin/shared/authentication/authorization-code-flow-native) lại minh họa bằng scope legacy `r_liteprofile`. **Phương án xử lý chuẩn:** Thử nghiệm với `scope=openid profile` trên App Member. Tuyệt đối **không fallback về `r_liteprofile`** vì scope này đã chính thức bị LinkedIn khai tử (deprecated từ 01/08/2023) và việc tự ý đổi scope sẽ vô hiệu hóa toàn bộ token đã cấp trước đó của app. Đối với App Org (không có OIDC), app trích xuất trực tiếp URN của quản trị viên qua trường `roleAssignee` từ response của `GET /rest/organizationAcls?q=roleAssignee` mà không cần gọi OIDC hay `/v2/userinfo`. Nếu Native PKCE trên App Member từ chối `openid`, đánh dấu tính năng kết nối trang cá nhân là blocked để chờ hỗ trợ từ LinkedIn.
- **Trao đổi Token bằng PKCE (Không có Client Secret):**
  ```http
  POST https://www.linkedin.com/oauth/v2/accessToken
  Content-Type: application/x-www-form-urlencoded

  grant_type=authorization_code
  &code={authorization_code}
  &redirect_uri=http://127.0.0.1:{port}/callback
  &client_id={client_id}
  &code_verifier={code_verifier}
  ```
  *(Lưu ý về độ dài token: Độ dài của token và authorization code không cố định; ứng dụng client cần có khả năng xử lý token có độ dài tối thiểu 1.000 ký tự [LinkedIn OAuth 2.0 Native Clients (PKCE)](https://learn.microsoft.com/en-us/linkedin/shared/authentication/authorization-code-flow-native), [LinkedIn Refresh Tokens with OAuth 2.0](https://learn.microsoft.com/en-us/linkedin/shared/authentication/programmatic-refresh-tokens)).*

#### Luồng B — Tài liệu thiết kế tham khảo cho Backend/BFF (Ngoài phạm vi Milestone 1)

> Phần này chỉ mô tả Standard OAuth 2.0 để tham khảo khi nhóm có backend/BFF riêng.
> Trong Milestone 1, nếu Native PKCE chưa được LinkedIn bật cho App ID, tính năng LinkedIn
> được đánh dấu **blocked**; không triển khai Luồng B trong repository Android.

##### Standard OAuth 2.0 (`/oauth/v2/...`) qua Kiến trúc BFF (Backend Proxy)
Nếu LinkedIn chưa kịp duyệt cấp cờ Native PKCE cho App ID của nhóm:
- **Authorization Endpoint:** `https://www.linkedin.com/oauth/v2/authorization`
- **Token Endpoint:** `https://www.linkedin.com/oauth/v2/accessToken`
- **Redirect URI đăng ký với LinkedIn:** Phải là **URL hợp lệ đã đăng ký trên Developer Portal** (theo [LinkedIn 3-Legged OAuth Flow](https://learn.microsoft.com/en-us/linkedin/shared/authentication/authorization-code-flow), ví dụ: `https://dev.example.com/auth/linkedin/callback`; trong thiết kế kiến trúc tham khảo của nhóm, đây là endpoint HTTPS trỏ về máy chủ backend/BFF như `https://api.yourdomain.com/auth/linkedin/callback` hoặc endpoint Cloudflare Worker).
  > [!IMPORTANT]
  > **Yêu cầu Callback của Standard OAuth:**
  > Theo tài liệu chính thức của LinkedIn OAuth 2.0 [LinkedIn 3-Legged OAuth Flow](https://learn.microsoft.com/en-us/linkedin/shared/authentication/authorization-code-flow), Redirect URI được cấu hình phải là một URL hợp lệ (ví dụ: `https://dev.example.com/auth/linkedin/callback`); Developer Portal thường chỉ hỗ trợ URL web chuẩn (HTTPS trong production hoặc `http://localhost`, `http://127.0.0.1` trong dev) chứ không hỗ trợ cấu hình Custom Scheme trực tiếp cho Standard Web OAuth.
- **Ràng buộc kỹ thuật:** Luồng standard này **bắt buộc phải có `client_secret`** khi gọi token endpoint.
- **Quy trình hoạt động hoàn chỉnh của Kiến trúc BFF (Backend-For-Frontend) kèm Bảo mật Kép:**
  1. **Khởi tạo ủy quyền & Đăng ký phiên với BFF (App-to-BFF PKCE):**
     - **Tạo bằng chứng sở hữu (Proof-of-Possession):** Trước khi mở browser, Android app sinh chuỗi ngẫu nhiên bảo mật `state` và cặp khóa PKCE nội bộ: `app_code_verifier` (chuỗi ngẫu nhiên bí mật độ entropy cao, lưu trong RAM) và `app_code_challenge = BASE64URL(SHA256(app_code_verifier))`.
     - **Khởi tạo phiên trên BFF qua endpoint `POST /auth/linkedin/start`:**
       - Để server BFF biết lưu trữ và theo dõi `state` trước khi nhận callback từ LinkedIn, app gửi request khởi tạo phiên:
       ```http
       POST https://api.yourdomain.com/auth/linkedin/start
       Content-Type: application/json

       {
         "state": "{state}",
         "code_challenge": "{app_code_challenge}"
       }
       ```
       - Server BFF lưu trữ cặp `(state, app_code_challenge)` vào bộ nhớ cache tạm thời (ví dụ Redis / memory store với TTL ngắn ~5–10 phút) để đối chiếu khi nhận callback.
     - **Mở Custom Tabs ủy quyền:** Sau khi phiên được khởi tạo trên BFF, Android app mở Custom Tabs điều hướng tới `https://www.linkedin.com/oauth/v2/authorization` với `redirect_uri=https://api.yourdomain.com/auth/linkedin/callback` (HTTPS URL tới BFF) và tham số `state`.
       > [!NOTE]
       > **Redirect URI trên LinkedIn Developer Portal (Production vs Môi trường Dev):**
       > Trong môi trường production, LinkedIn bắt buộc Redirect URI phải dùng giao thức bảo mật `https://`. Tuy nhiên, trong quá trình phát triển nội bộ hoặc kiểm thử cục bộ (local dev), LinkedIn Developer Portal cho phép đăng ký URL dạng `http://localhost` hoặc `http://127.0.0.1` kèm cổng cụ thể (ví dụ: `http://127.0.0.1:8080/callback`) để nhà phát triển dễ dàng test OAuth flow trực tiếp mà không bắt buộc phải có domain HTTPS ngay.
  2. **Callback HTTPS từ LinkedIn về BFF:** Người dùng đăng nhập và xác nhận cấp quyền trên trình duyệt. Máy chủ xác thực LinkedIn chuyển hướng (redirect) trình duyệt trực tiếp về endpoint HTTPS của BFF kèm mã:
     `https://api.yourdomain.com/auth/linkedin/callback?code={authorization_code}&state={state}`
     - Server BFF nhận callback, kiểm tra đối chiếu tham số `state` nhận được với cache đã lưu ở bước `POST /auth/linkedin/start`. Nếu `state` không tồn tại hoặc đã hết hạn, BFF từ chối request ngay lập tức để chống CSRF.
  3. **BFF đổi Token với LinkedIn (Server-to-Server):** Server BFF (nơi lưu giữ `LINKEDIN_CLIENT_SECRET` an toàn trong biến môi trường server, tuyệt đối không đưa vào client/APK) gửi request POST trực tiếp lên máy chủ LinkedIn:
     ```http
     POST https://www.linkedin.com/oauth/v2/accessToken
     Content-Type: application/x-www-form-urlencoded

     grant_type=authorization_code
     &code={authorization_code}
     &redirect_uri=https://api.yourdomain.com/auth/linkedin/callback
     &client_id={client_id}
     &client_secret={client_secret}
     ```
  4. **BFF chuyển hướng về App bằng Android App Links & Mã Trao đổi Dùng Một Lần:**
     > [!CAUTION]
     > **Nguyên tắc phòng thủ chặn bắt Deep Link (Interception Attack Prevention):**
     > - Nếu chỉ dùng Custom Scheme (`com.lttbdd.socialagent://...`), một ứng dụng độc hại cài trên máy có thể đăng ký trùng scheme để nhận được cả `exchange_code` lẫn `state`. Việc app thật kiểm tra `state` chỉ chống được tấn công CSRF web, chứ **không ngăn được app độc hại lấy cắp `exchange_code` rồi gửi request đổi token trước app thật**.
     > - Do đó, hệ thống áp dụng **3 điểm phòng vệ then chốt**:
     >   1. **Lựa chọn chính thức (Primary):** Chuyển hướng bằng **Android App Links (HTTPS)**:
     >      ```
     >      https://socialagent.app/oauth/linkedin/callback?exchange_code={one_time_code}&state={state}
     >      ```
     >      *(Android App Links sử dụng file chứng thực số `/.well-known/assetlinks.json` trên domain để OS xác minh chữ ký SHA-256 của file APK, giúp **giảm đáng kể rủi ro** bị ứng dụng khác chặn bắt URL; tuy nhiên hành vi này vẫn phụ thuộc vào phiên bản Android và trạng thái xác minh domain thành công trên thiết bị).*
     >   2. **Lựa chọn dự phòng (Fallback):** Custom Scheme `com.lttbdd.socialagent://oauth/linkedin/callback?...` (khi domain chưa kịp xác minh hoặc trên thiết bị chưa hỗ trợ đầy đủ App Links).
     >   3. **Lớp bảo vệ bắt buộc ở cả hai lựa chọn (App-to-BFF PKCE):**
     >      Cơ chế **PKCE nội bộ (`app_code_verifier` và `app_code_challenge`) là lớp phòng thủ cốt lõi bắt buộc ở CẢ HAI lựa chọn chuyển hướng** (không chỉ riêng cho fallback). Ngay cả khi URL callback bị can thiệp hoặc lộ `exchange_code`, kẻ tấn công vẫn **hoàn toàn không thể đổi mã lấy token** vì thiếu `app_code_verifier` (chỉ tồn tại trong RAM của app thật ở bước 5).
     
     - Sau khi nhận và lưu trữ tạm thời phiên token từ LinkedIn, máy chủ BFF sinh một mã dùng một lần (**One-Time Exchange Code** / `exchange_code`), có thời gian sống cực ngắn (TTL $\le 60$ giây) gắn liền với `app_code_challenge` của phiên.
     - Server BFF phản hồi HTTP 302 Redirect chuyển hướng trình duyệt về ứng dụng Android qua Android App Links.
  5. **Đổi Token an toàn qua Kênh Bảo mật Back-Channel kèm `code_verifier` & Phân định Lưu trữ:**
     - Trình duyệt Custom Tabs nhận redirect và đóng lại; hệ điều hành Android kích hoạt Activity xử lý callback của app.
     - App kiểm tra đối chiếu tham số `state` nhận được với `state` đã lưu cục bộ để chống CSRF.
     - App gửi ngay request `POST` qua kết nối bảo mật HTTPS trực tiếp tới BFF, đính kèm `code_verifier` nội bộ:
       ```http
       POST https://api.yourdomain.com/auth/linkedin/token
       Content-Type: application/json

       {
         "exchange_code": "{one_time_code}",
         "state": "{state}",
         "code_verifier": "{app_code_verifier}"
       }
       ```
     - **Xác thực kép tại BFF:** Server BFF kiểm tra `exchange_code`, đồng thời băm `SHA256(code_verifier)` so khớp với `app_code_challenge`. Kẻ tấn công nếu có chặn bắt được `exchange_code` trên deep link cũng **hoàn toàn không thể đổi mã** vì không có `app_code_verifier` (chỉ lưu trong RAM của app thật).
     - BFF hủy mã `exchange_code` ngay lập tức (one-time use), tạo mã phiên `bff_session_token` độ entropy cao (hoặc JWT ký số bởi server), và trả về payload JSON chứa cả access token và session token qua HTTPS response body an toàn:
       ```json
       {
         "access_token": "AQXN...",
         "token_type": "bearer",
         "expires_in": 5184000,
         "bff_session_token": "{bff_session_token}"
       }
       ```
       > *Ghi chú về `expires_in`:* Giá trị `5184000` (~60 ngày) là số giây ví dụ minh họa. Máy chủ BFF bắt buộc phải **chuyển tiếp nguyên vẹn trường `expires_in` thực tế** nhận được từ LinkedIn về cho App, và App client lưu `expires_at = System.currentTimeMillis() + (response.expires_in * 1000L)` (tuyệt đối không gán cứng hằng số 60 ngày).
     - **Cơ chế cấp & vai trò của `bff_session_token` phía BFF:**
       - **Cách cấp:** BFF sinh mã phiên ngẫu nhiên độ entropy cao (hoặc JWT ký bởi secret server) và gửi trực tiếp cho App trong response body của endpoint `POST https://api.yourdomain.com/auth/linkedin/token` (kênh HTTPS back-channel bảo mật, tuyệt đối không gửi qua URL/Deep Link).
       - **Vai trò:** `bff_session_token` là khóa định danh phiên bí mật giữa App và BFF. Token này đảm nhận hai vai trò an ninh then chốt:
         1. **Làm mới token tự động:** Được app đính kèm trong header `Authorization: Bearer {bff_session_token}` khi gọi `POST /auth/linkedin/refresh` (mục 3 dưới đây), giúp BFF chứng thực chính xác app thật trước khi dùng `refresh_token` lưu trên server để đổi lấy token mới với LinkedIn.
         2. **Thu hồi phiên & Hủy `refresh_token` khi Ngắt kết nối (`POST /auth/linkedin/logout`):** Khi người dùng bấm "Ngắt kết nối" (F1), app gửi `bff_session_token` lên endpoint `POST /auth/linkedin/logout` để yêu cầu BFF xóa vĩnh viễn `refresh_token` trong DB máy chủ và vô hiệu hóa phiên, ngăn ngừa nguy cơ tồn đọng credential mồ côi trên backend sau khi app đã hủy kết nối.
     - **Quy tắc phân định lưu trữ an toàn phía Client:**
       - **Chỉ lưu Token trong `SecureTokenStore`:** Toàn bộ token bí mật gồm `access_token`, `refresh_token` (nếu có) và `bff_session_token` được lưu trữ an toàn trong `SecureTokenStore` (DataStore Preferences + Android Keystore AES-GCM; không dùng `EncryptedSharedPreferences` đã bị deprecate, chi tiết tại §5.3).
       - **Room Database tuyệt đối KHÔNG lưu token:** Room chỉ lưu trữ metadata phi nhạy cảm của tài khoản (`account_id`, `name`, `avatar_url`, `expires_at`, `connection_status`, `capabilities`). Khi người dùng đăng nhập lần đầu, app đồng thời ghi nhận mốc `refreshTokenIssuedAt = System.currentTimeMillis()` vào Room DB (nếu kích hoạt luồng refresh) để làm căn cứ xác định hạn trần 365 ngày của refresh token. Khi gọi API, Repository sẽ lấy metadata từ Room và truy xuất token từ `SecureTokenStore`.

#### Bảng so sánh hai luồng OAuth của LinkedIn

| Tiêu chí | Luồng A: Native PKCE (`/oauth/native-pkce/`) | Luồng B: Standard OAuth (`/oauth/v2/`) qua BFF |
|---|---|---|
| **Endpoint cấp mã** | `https://www.linkedin.com/oauth/native-pkce/authorization` | `https://www.linkedin.com/oauth/v2/authorization` |
| **Kiểu Redirect URI** | Loopback IP (`http://127.0.0.1:{port}/callback`) | URL HTTPS về server BFF (`https://api.yourdomain.com/.../callback`) *(App nhận mã `exchange_code` tạm thời qua Android App Links rồi gọi POST HTTPS lấy token)* |
| **Yêu cầu Client Secret** | ❌ **Không** (Trao đổi bằng `code_verifier`) | ✅ **Bắt buộc** (Phải ủy quyền và giữ secret tại Backend/BFF) |
| **Điều kiện kích hoạt** | Cần liên hệ **LinkedIn Support duyệt bật cờ Native PKCE** | Endpoint Standard OAuth là luồng web có sẵn, nhưng product, scope, redirect URI và quyền dùng thực tế vẫn phải được xác nhận trên Developer Portal |
| **Phù hợp đồ án** | Tối ưu khi đã được LinkedIn duyệt cờ Native PKCE | Dùng dự phòng nếu chưa kịp duyệt cờ PKCE từ LinkedIn |

#### Vòng đời Token & Cơ chế Tái xác thực (Token Lifecycle & Re-authentication)

> Refresh token của LinkedIn không phải entitlement mặc định. Chỉ dùng khi ứng dụng được cấp
> programmatic refresh token; đối chiếu [Programmatic Refresh Tokens](https://learn.microsoft.com/en-us/linkedin/shared/authentication/programmatic-refresh-tokens).

> [!CAUTION]
> **Ràng buộc nền tảng về Programmatic Refresh Token của LinkedIn:**
> - `offline_access` và refresh token **không phải capability mặc định có thể suy ra cho mọi App**. Chỉ đưa scope này vào authorization request khi tài liệu/product và Developer Portal của App xác nhận hỗ trợ; nếu không, request có thể bị từ chối.
> - Quyền cấp **Refresh Token (TTL 365 ngày)** để làm mới ngầm qua API là tính năng phụ thuộc entitlement/phê duyệt của LinkedIn, thường gắn với chương trình đối tác như Marketing Developer Platform (MDP). **Đồ án sinh viên không nên giả định được cấp**, nên phương án refresh chỉ là kiến trúc tham khảo và không được dùng làm đường vận hành duy nhất.
> - **Nguyên tắc thiết kế F1:** Ứng dụng **tuyệt đối không phụ thuộc hoàn toàn vào Refresh Token**; nếu không được cấp, dùng luồng re-authentication khi access token hết hạn theo `expires_in` thực tế, không coi 60 ngày là hằng số runtime.

1. **Vòng đời Access Token Tiêu chuẩn:** Theo tài liệu LinkedIn, access token **thường có thời hạn mặc định 60 ngày**; ứng dụng phải dùng `expires_in` thực tế thay vì hard-code thời hạn. Đặc biệt, theo tài liệu Native PKCE [LinkedIn OAuth 2.0 Native Clients (PKCE)](https://learn.microsoft.com/en-us/linkedin/shared/authentication/authorization-code-flow-native#access-token-scopes-and-lifetime), nếu người dùng thực hiện luồng cấp quyền với **tập scope khác so với scope đã cấp trước đó** (dù trên cùng client hay client khác), thì **toàn bộ các access token đã cấp trước đó đều bị vô hiệu hóa ngay lập tức**.
2. **Phương án Xử lý khi Hết hạn Token (Re-authentication Flow — Cho ứng dụng thông thường):**
   - **Theo dõi thời hạn:** Khi đăng nhập thành công, app lưu `expires_at = System.currentTimeMillis() + (expires_in * 1000L)` vào Room Database (app không suy đoán bằng hằng số 60 ngày mà lưu thời hạn thực tế từ `expires_in`).
   - **Phân định trạng thái Cảnh báo Sắp hết hạn và Thực sự hết hạn (B5):**
     - **Khi thời gian còn lại $\le 72$ giờ (dưới 3 ngày) nhưng token chưa hết hạn:** UI tính toán từ `expiresAt` so với thời gian hiện tại để hiển thị Badge cảnh báo **"Sắp hết hạn"** trên màn hình F1 và F4, nhắc nhở người dùng chủ động kết nối lại. **Tuyệt đối không đổi `connectionStatus` sang `TOKEN_EXPIRED` khi token vẫn còn thời hạn hiệu lực**, nhằm tránh làm gián đoạn các thao tác của người dùng và giữ nguyên vẹn hợp đồng `ConnectionStatus` trong `core/model/Channel.kt` (tuân thủ Quy tắc 1 của `AGENTS.md`).
     - **Khi token thực sự hết hạn (`expiresAt <= System.currentTimeMillis()`) hoặc khi nhận mã HTTP `401 Unauthorized` từ API:** Repository mới cập nhật trạng thái kênh trong Room DB thành `ConnectionStatus.TOKEN_EXPIRED`.
   - **Trải nghiệm người dùng (UX):**
     - Khi ở trạng thái sắp hết hạn, UI gợi ý: *"Phiên kết nối sắp hết hạn trong 3 ngày tới. Bạn nên kết nối lại sớm"*. Khi đã thực sự hết hạn (`TOKEN_EXPIRED`), UI hiển thị: *"Phiên kết nối LinkedIn đã hết hạn. Vui lòng kết nối lại"*.
     - Nút "Kết nối lại" (Re-connect) kích hoạt mở lại luồng Native PKCE (Custom Tabs / Loopback). Trình duyệt hệ thống có thể vẫn lưu phiên đăng nhập, nhưng API vẫn có thể yêu cầu consent hoặc đăng nhập lại đầy đủ; UX không được coi là luôn "1 chạm".
3. **Phương án Dự phòng khi được cấp Programmatic Refresh Token (Nếu được duyệt Partner Tier — Thực tế rất khó áp dụng với đồ án):**
   > [!IMPORTANT]
   > **Yêu cầu bắt buộc `client_secret` khi Refresh Token:**
   > Theo tài liệu chính thức của LinkedIn OAuth 2.0 (`POST /oauth/v2/accessToken` với `grant_type=refresh_token`), request làm mới token **bắt buộc phải truyền cả `client_id` và `client_secret`**.
   > Vì ứng dụng Android là Public Client không được giữ `client_secret`, **request refresh token này BẮT BUỘC phải do máy chủ BFF thực hiện** (nơi lưu giữ `client_secret` an toàn phía server), tuyệt đối không gọi trực tiếp từ Android client.

   > [!WARNING]
   > **Phạm vi xung đột với file `AGENTS.md` (Quy tắc bắt buộc số 5: Bảo mật API key & token) — Rộng hơn chỉ refresh token:**
   > - **Quy tắc số 5 trong file `AGENTS.md`:** *"Không bao giờ log, commit hay gửi API key/token lên bất kỳ server nào ngoài nhà cung cấp LLM/mạng xã hội. Key lưu bằng Android Keystore."*
   > - **Phạm vi mâu thuẫn thực tế:**
   >   - **Luồng B (LinkedIn) và Phương án 2 (Facebook Web OAuth qua BFF):** Trong cả hai luồng này, access token phải đi qua bộ nhớ cache tạm thời của BFF (TTL ≤ 60 giây) trong bước đổi `exchange_code`. Đây vẫn là "gửi token lên server ngoài nhà cung cấp" theo đúng nghĩa của Quy tắc 5, dù chỉ tạm thời và không ghi vào database lâu dài.
   >   - **Luồng lưu `refresh_token` vào DB server (nếu được duyệt Partner Tier):** Mức độ vi phạm sâu hơn — token tồn tại lâu dài (365 ngày) trên database máy chủ BFF.
   > - **Kết luận về phạm vi tuân thủ:**
   >   - ✅ **Tuân thủ hoàn toàn Quy tắc 5:** **LinkedIn Luồng A (Native PKCE)** + **Facebook Phương án 1 (Meta SDK)** — token không đi qua bất kỳ server trung gian nào của dự án.
   >   - ⚠️ **Cần ngoại lệ Policy Exception:** **LinkedIn Luồng B** và **Facebook Phương án 2 (Web OAuth qua BFF)** — access token đi qua BFF cache ≤ 60 giây; chấp nhận được về rủi ro kỹ thuật nhưng về chữ nghĩa Quy tắc 5 thì cần nhóm thống nhất.
   >   - ❌ **Xung đột rõ ràng, cần Policy Exception qua issue `contract`:** Lưu `refresh_token` LinkedIn vào DB server — phương án này thực tế **rất khó áp dụng** với đồ án vì yêu cầu đủ điều kiện MDP Partner.
   > - **Baseline hiện tại (cập nhật sau TEST-06):** LinkedIn App Member dùng **Luồng B qua BFF** vì Native PKCE không chấp nhận `openid`;
   >   Facebook dùng **Phương án 1 (Meta SDK)**. LinkedIn chỉ chạy thật khi có BFF được triển khai và `LINKEDIN_BFF_URL` được cấu hình;
   >   chưa có thì app ở chế độ demo. Facebook Phương án 2 (Web OAuth qua BFF) vẫn không dùng.
   > - **Điều kiện tiên quyết:** Trước khi triển khai bất kỳ luồng nào qua BFF, **toàn nhóm bắt buộc phải họp thống nhất và ghi nhận phạm vi ngoại lệ (Policy Exception) cho Quy tắc 5 trong `AGENTS.md`** thông qua ticket/issue nhãn `contract`.

   - **Thực thi hoàn toàn phía Server/BFF:**
     - **Cơ chế xác thực giữa App và BFF (BFF Session Authentication):**
       - Khi hoàn tất đăng nhập ban đầu, máy chủ BFF lưu trữ `refresh_token` của LinkedIn an toàn trong cơ sở dữ liệu server (được mã hóa at-rest).
       - BFF cấp cho app Android một token phiên nội bộ (**BFF Session Token**, chuỗi ngẫu nhiên độ entropy cao hoặc JWT ký bởi server). App lưu Session Token này riêng biệt trong `EncryptedSharedPreferences`.
       - Khi token sắp hết hạn (thời gian hiệu lực còn lại dưới 3 ngày / 72 giờ, đồng bộ với ngưỡng cảnh báo F1, phát hiện qua trường `expiresAt` trong Room DB), app Android gửi yêu cầu làm mới ngầm đến server BFF qua kết nối HTTPS:
         ```http
         POST https://api.yourdomain.com/auth/linkedin/refresh
         Authorization: Bearer {bff_session_token}
         Content-Type: application/json

         {
           "account_id": "urn:li:organization:{org-id}"
         }
         ```
       - **BFF xác thực phiên:** Server BFF xác minh tính hợp lệ và chữ ký của `bff_session_token`, kiểm tra xem token phiên có quyền sở hữu hợp pháp đối với `account_id` yêu cầu hay không. Nếu phiên không hợp lệ hoặc đã bị thu hồi, BFF từ chối request ngay lập tức với mã HTTP 401.
     - **BFF làm mới token với LinkedIn:**
       - Sau khi xác thực đúng phiên app, server BFF lấy `refresh_token` tương ứng trong DB cùng `client_secret` (lưu trong biến môi trường server) để gọi LinkedIn:
         ```http
         POST https://www.linkedin.com/oauth/v2/accessToken
         Content-Type: application/x-www-form-urlencoded

         grant_type=refresh_token
         &refresh_token={refresh_token}
         &client_id={client_id}
         &client_secret={client_secret}
         ```
     - **Phản hồi, Tính toán Thời hạn Thực tế & Lưu trữ phía Client:**
       - Server BFF nhận Access Token mới cùng trường `expires_in` biểu thị thời hạn hiệu lực thực tế (số giây còn lại) từ LinkedIn, và chuyển tiếp nguyên vẹn `expires_in` này trong JSON response body cho app Android qua kênh HTTPS bảo mật:
         ```json
         {
           "access_token": "AQXN...",
           "token_type": "bearer",
           "expires_in": 5184000
         }
         ```
         > *Ghi chú về `expires_in`:* Giá trị `5184000` (~60 ngày) chỉ là số giây ví dụ minh họa. Máy chủ BFF bắt buộc phải **chuyển tiếp nguyên vẹn trường `expires_in` thực tế** nhận được từ LinkedIn về cho App (không tự ý làm tròn hay gán hằng số).
       - **Cách tính hạn token sau refresh (Token Expiration Calculation):**
         - **Không suy diễn thời hạn từ mốc mặc định:** Thời hạn sau refresh phụ thuộc response và chính sách/product của LinkedIn; không giả định luôn là 60 ngày hoặc tự suy ra từ một mốc refresh token.
         - **App client tính toán động theo `expires_in` thực tế (Tuyệt đối không cộng cố định 60 ngày):**
           App client không suy hạn bằng hằng số 60 ngày. Mỗi lần nhận token (khi đăng nhập ban đầu hoặc khi refresh), app đều đọc giá trị `expires_in` thực tế do BFF chuyển tiếp từ LinkedIn để tính và lưu vào Room DB:
           $$\text{expires\_at} = \text{System.currentTimeMillis}() + (\text{response.expires\_in} \times 1000\text{L})$$
           *(Công thức này đồng bộ chính xác với cách tính hạn ở lần đăng nhập đầu tiên tại mục 2, đảm bảo app phản ánh trung thực hạn token thực tế và hiển thị badge cảnh báo kịp thời kể cả trong các chu kỳ refresh cuối cùng).*
         - **Mốc hết hạn Refresh Token (Tính từ `refreshTokenIssuedAt`):**
           - Với Programmatic Refresh Tokens được LinkedIn cấp, tài liệu mô tả refresh token có thời hạn tối đa 365 ngày kể từ thời điểm cấp quyền ban đầu và **không được gia hạn** khi refresh.
           - **Lưu ý về trường `refresh_token_expires_in`:** Theo tài liệu [LinkedIn Refresh Tokens with OAuth 2.0](https://learn.microsoft.com/en-us/linkedin/shared/authentication/programmatic-refresh-tokens), trường này biểu thị số giây còn lại cho đến khi refresh token hết hạn (*number of seconds remaining*). Tuy nhiên, các ví dụ phản hồi trong tài liệu hiển thị các giá trị số nguyên như `525600` hoặc `439200` (có thể gây hiểu nhầm nếu đối chiếu với các mốc ngày/phút). Để đảm bảo tính xác định và tránh sai lệch từ phía server, app nên quản lý mốc thời hạn 365 ngày của refresh token bằng cách tính toán trực tiếp từ thời điểm cấp quyền ban đầu `refreshTokenIssuedAt`:
             $$\text{refreshTokenDeadline} = \text{refreshTokenIssuedAt} + (365\text{L} \times 24 \times 3600 \times 1000\text{L})$$
         - **Ngưỡng kích hoạt làm mới & Cơ chế chống lặp Refresh vô tận (Infinite Refresh Loop Prevention):**
           - **Ngưỡng kích hoạt:** Đồng bộ thống nhất với ngưỡng cảnh báo F1 là **dưới 3 ngày (72 giờ)**. Khi thời hạn còn lại của token $\le 3\text{ ngày}$ (phát hiện qua `expiresAt` trong Room DB), app mới kích hoạt request gọi `POST /auth/linkedin/refresh` lên BFF.
           - **Xử lý nếu `expires_in` sau refresh $\le 3\text{ ngày}$ (ví dụ ở chu kỳ ngày thứ 360–365):**
             - Nếu sau khi refresh thành công mà `response.expires_in` trả về nhỏ hơn hoặc bằng ngưỡng cảnh báo 3 ngày (do refresh token đã chạm sát mốc 365 ngày):
               - Access token mới nhận vẫn hoàn toàn hợp lệ trong khoảng thời gian còn lại đó ($\text{expires\_in}$ giây). Do đó, app vẫn lưu token vào `SecureTokenStore` và cập nhật $\text{expiresAt} = \text{System.currentTimeMillis}() + (\text{response.expires\_in} \times 1000\text{L})$ trong Room DB.
               - **Tuyệt đối không gán ngay `ConnectionStatus.TOKEN_EXPIRED`** khi token vẫn còn thời hạn hiệu lực vì sẽ gây lỗi gián đoạn nghiệp vụ không đáng có cho người dùng.
               - Để ngăn ngừa refresh lặp vô tận (Infinite Loop), Repository kích hoạt cờ cảnh báo `needsReauth = true` (hoặc trạng thái cảnh báo riêng `NEEDS_REAUTH`), đồng thời **vô hiệu hóa hoàn toàn tiến trình auto-refresh ngầm** cho tài khoản này.
               - Giao diện F1/F4 hiển thị Badge cảnh báo: *"Phiên kết nối LinkedIn sắp hết hạn chu kỳ 365 ngày. Vui lòng kết nối lại sớm"* để người dùng chủ động Re-authentication 1 chạm qua trình duyệt.
               - Kênh chỉ thực sự chuyển sang `ConnectionStatus.TOKEN_EXPIRED` khi $\text{expiresAt} \le \text{System.currentTimeMillis}()$ hoặc khi API thực tế trả về lỗi xác thực token.
           - Sau mốc 365 ngày kể từ `refreshTokenIssuedAt`, mọi yêu cầu làm mới ngầm đều bị chặn; người dùng bắt buộc Re-authentication qua trình duyệt.
       - **Lưu trữ chuẩn:** App cập nhật Access Token mới trực tiếp vào **`SecureTokenStore`**. Room DB **chỉ cập nhật metadata** `expiresAt` (tính theo công thức động ở trên) và `ConnectionStatus.CONNECTED` (tuyệt đối không ghi token vào Room).
   - **Quy trình Thu hồi Phiên & Hủy `refresh_token` trên BFF khi Ngắt kết nối (`POST /auth/linkedin/logout`):**
     - **Rủi ro tồn đọng credential mồ côi (Orphaned Refresh Token Risk):** Khi app được duyệt quyền Partner Tier, máy chủ BFF lưu giữ `refresh_token` (thời hạn 365 ngày) trong cơ sở dữ liệu server để làm mới ngầm. Nếu khi người dùng bấm "Ngắt kết nối" (Disconnect) trên ứng dụng, app chỉ xóa token cục bộ trong `SecureTokenStore` mà không gửi lệnh hủy lên BFF, thì `refresh_token` vẫn tồn tại hợp lệ trên DB server, gây nguy cơ rò rỉ bảo mật và xung đột trạng thái nếu người dùng kết nối lại sau này.
     - **Cơ chế thu hồi phiên bắt buộc:** Khi người dùng bấm "Ngắt kết nối" (F1) tài khoản LinkedIn, ứng dụng Android gửi request thu hồi phiên lên BFF:
       ```http
       POST https://api.yourdomain.com/auth/linkedin/logout
       Authorization: Bearer {bff_session_token}
       Content-Type: application/json

       {
         "account_id": "urn:li:organization:{org-id}"
       }
       ```
     - **Xử lý tại Máy chủ BFF:**
       1. **Xác thực phiên:** BFF xác minh tính hợp lệ và quyền sở hữu của `bff_session_token` đối với `account_id`.
       2. **Xóa sạch Refresh Token:** BFF xóa ngay lập tức bản ghi `refresh_token` và access token cache tương ứng của tài khoản khỏi cơ sở dữ liệu server.
       3. **Vô hiệu hóa phiên:** Đưa `bff_session_token` vào danh sách thu hồi (revoked sessions store / blacklist), ngăn chặn mọi yêu cầu gọi `POST /auth/linkedin/refresh` về sau sử dụng phiên này.
       4. Trả về HTTP 200 OK (hoặc 204 No Content).
     - **Xử lý phía App Client:** Sau khi gửi request logout (theo cơ chế best-effort nếu mất kết nối mạng), app tiến hành xóa sạch `access_token`, `refresh_token` (nếu có), và `bff_session_token` trong `SecureTokenStore`, đồng thời cập nhật/xóa metadata trong Room DB (chi tiết tại §5.3).
   - **Lưu ý với Client Native thuần túy (không có BFF):** Nếu chạy Luồng A (Native PKCE trực tiếp) mà không có backend server, tính năng programmatic refresh thường không thể thực thi do yêu cầu `client_secret` và entitlement tương ứng. Khi đó, app phải theo dõi `expires_in` thực tế và khởi động lại luồng OAuth khi token hết hạn; không hard-code chu kỳ 60 ngày.
   - *Thời hạn tối đa:* Với Programmatic Refresh Tokens được cấp cho app, tài liệu LinkedIn mô tả refresh token có TTL 365 ngày; mỗi lần refresh cấp access token mới với TTL tối đa 60 ngày, nhưng ở cuối chu kỳ cả hai token có thể còn cùng thời gian sống. Refresh không gia hạn refresh token; sau 365 ngày kể từ `refreshTokenIssuedAt` (hoặc khi refresh thất bại), người dùng bắt buộc phải re-authenticate qua trình duyệt.

### 3.2. Danh sách quyền (Permissions / Scopes)
Đối chiếu trực tiếp với các khả năng trong `ChannelCapability` (`core/model/Channel.kt`) theo chuẩn LinkedIn REST API & Community Management API:

> **Lưu ý về API tương tác:** Các trang tài liệu LinkedIn được đối chiếu ở đây vẫn mô tả
> `socialActions/comments` cho thao tác đọc/ghi comment. Không tự suy ra rằng một API khác đã thay thế
> endpoint này; trước khi triển khai production phải xác nhận endpoint, product, version và permission
> được Developer Portal cấp cho ứng dụng.

| Chức năng / Khả năng | Scope cần xin | Mục đích & Chi tiết kỹ thuật |
|---|---|---|
| **F1 (Kết nối & Quản trị)** | **App Member:** `openid`, `profile`<br>**App Org:** `r_organization_admin` (hoặc `rw_organization_admin`) | **App Member:** Xác thực OIDC để lấy URN cá nhân (`sub`) qua `GET /v2/userinfo`.<br>**App Org:** Do Community Management API bắt buộc là sản phẩm duy nhất trên developer app (không thể có `openid profile`), URN thành viên được trích xuất từ trường `roleAssignee` (`urn:li:person:{id}`) trong response `GET /rest/organizationAcls?q=roleAssignee` [LinkedIn Organization Access Control by Role](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/organizations/organization-access-control-by-role). *(Scope `r_organization_admin` là đủ cho thao tác đọc danh sách ACL; chỉ cần `rw_organization_admin` nếu muốn phân quyền hoặc sửa đổi vai trò (lưu ý: các endpoint thống kê F7 như Follower Statistics và Share Statistics yêu cầu cứng `rw_organization_admin`, trong khi `networkSizes` không quy định scope này trong tài liệu tham chiếu). Yêu cầu vai trò `ADMINISTRATOR` hoặc `CONTENT_ADMINISTRATOR` theo bảng phân quyền Organization [LinkedIn Organization Access Control by Role](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/organizations/organization-access-control-by-role); lưu ý [LinkedIn Posts API](https://learn.microsoft.com/en-us/linkedin/marketing/integrations/community-management/shares/posts-api#permissions) dùng tên viết tắt `CONTENT_ADMIN`; Comments API chỉ hỗ trợ role `ADMINISTRATOR`, `DIRECT_SPONSORED_CONTENT_POSTER`, `RECRUITING_POSTER` cho quyền ghi `w_organization_social_feed`, và chỉ hỗ trợ `ADMINISTRATOR`, `DIRECT_SPONSORED_CONTENT_POSTER` cho quyền đọc `r_organization_social_feed` [LinkedIn Comments API](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/shares/comments-api)).* |
| **`PUBLISH` (Đăng bài)** | `w_organization_social` *(Page)*<br>`w_member_social` *(Cá nhân)* | Đăng bài viết (text, link, media URN) lên Organization Page hoặc trang cá nhân của thành viên [LinkedIn Posts API](https://learn.microsoft.com/en-us/linkedin/marketing/integrations/community-management/shares/posts-api). |
| **`READ_COMMENTS` (Đọc comment)** | `r_organization_social_feed` (Page)<br>*(Cá nhân: Bị hạn chế / Cần kiểm tra)* | Đọc danh sách bình luận, các reply phân cấp và reaction trên bài viết của Organization Page (`GET /rest/socialActions/{targetUrn}/comments`) [LinkedIn Comments API](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/shares/comments-api). **Yêu cầu:** Sản phẩm Community Management API và role được tài liệu endpoint cho phép.<br>⚠️ *Đối với Member cá nhân:* Tài liệu Comments API và Social Metadata API quy định quyền đọc tương tác cá nhân `r_member_social_feed` chỉ cấp cho đối tác được chọn lọc (*granted to select developers only*) [LinkedIn Comments API](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/shares/comments-api), [LinkedIn Social Metadata API](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/shares/social-metadata-api). Nếu app chưa được LinkedIn duyệt cấp scope này, kích hoạt luồng `HANDED_OFF`. |
| **`REPLY` (Trả lời comment)** | `w_organization_social_feed` (Page)<br>`w_member_social_feed` (Cá nhân - cần xác nhận) | Gửi bình luận trả lời (`POST /rest/socialActions/{targetUrn}/comments`) dưới danh nghĩa `urn:li:organization:{org-id}` [LinkedIn Comments API](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/shares/comments-api). **Yêu cầu:** Sản phẩm Community Management API và role được tài liệu endpoint cho phép.<br>⚠️ *Đối với Member cá nhân:* Trang Network Update Social Actions và Comments API liệt kê scope `w_member_social_feed` cho các hành động tương tác cá nhân [LinkedIn Network Update Social Actions](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/shares/network-update-social-actions), [LinkedIn Comments API](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/shares/comments-api). Cần kiểm tra trên Developer Portal xem app có xin được scope này không; nếu không, fallback về `HANDED_OFF`. |
| **`MENTION` (Nhắc tên)** | `w_organization_social` *(chỉ là quyền đăng; capability phải xác nhận riêng)* | ⚠️ Không coi mention Member/Organization là capability chung của LinkedIn Posts API. Chỉ thử khi tài liệu của product/version hiện hành xác nhận cú pháp, URN và quyền [LinkedIn Little Text Format](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/shares/little-text-format); nếu không xác nhận được thì dùng luồng `HANDED_OFF`. LinkedIn không cung cấp API tìm kiếm profile URN tự do cho app thông thường. |
| **`METRICS` (Số liệu)** | `rw_organization_admin` (quy định chính thức cho [LinkedIn Organization Share Statistics](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/organizations/share-statistics) và [LinkedIn Organization Follower Statistics](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/organizations/follower-statistics); cần kiểm thử kèm `r_organization_social` (Cần kiểm thử thực tế) [TEST-05]); yêu cầu vai trò `ADMINISTRATOR` trên Organization Page. (Lưu ý: Endpoint `networkSizes` [LinkedIn Organization Network Size](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/organizations/organization-network-size) không quy định scope `rw_organization_admin` hay vai trò `ADMINISTRATOR`, điều kiện này chỉ bắt buộc cho `Follower Statistics` bên dưới).<br>*(Cá nhân: Bị hạn chế / Cần kiểm tra)* | Đọc số liệu tương tác (`likeCount`, `commentCount`, `shareCount`, `impressionCount`, `clickCount`) qua `organizationalEntityShareStatistics` [LinkedIn Organization Share Statistics](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/organizations/share-statistics) và số người theo dõi (`firstDegreeSize`) qua `networkSizes` [LinkedIn Organization Network Size](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/organizations/organization-network-size).<br>⚠️ *Đối với Member cá nhân:* Tài liệu Increasing Access hỗ trợ quyền `r_member_postAnalytics` (đọc impressions, reactions, comments, shares) và `r_member_profileAnalytics` thuộc Community Management API [LinkedIn Increasing Access](https://learn.microsoft.com/en-us/linkedin/marketing/increasing-access?view=li-lms-2026-10). Nếu app chưa được duyệt cấp các quyền này trên Portal, kích hoạt `HANDED_OFF`. |
| **Làm mới ngầm (F1 - Tùy chọn)** | Scope và entitlement do Developer Portal/product cấp; không tự thêm `offline_access` nếu chưa được xác nhận | Programmatic Refresh Token phụ thuộc entitlement/phê duyệt của LinkedIn. Tài liệu hiện hành mô tả refresh token có TTL 365 ngày và không được gia hạn khi refresh; access token mới thường có TTL 60 ngày nhưng phải dùng `expires_in` thực tế. Request refresh phải thực thi qua BFF kèm `client_secret`. |

### 3.3. Các Endpoint chính (LinkedIn Versioned REST API)

> [!IMPORTANT]
> **Chính sách Phiên bản & Vòng đời API của LinkedIn (Version Sunset Policy):**
> - LinkedIn áp dụng cơ chế định phiên bản theo tháng với định dạng **`YYYYMM`** thông qua header `Linkedin-Version`.
> - **Thời hạn hỗ trợ:** Mỗi phiên bản API thường được hỗ trợ ổn định trong **tối thiểu 1 năm** kể từ ngày phát hành (thông lệ phổ biến dựa trên mốc sunset của version 202511 trong [LinkedIn MultiImage API](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/shares/multiimage-post-api); tài liệu chính thức hiện tại không có văn bản chung về Versioning Policy của LinkedIn, do đó ngày sunset cụ thể phải luôn được xác nhận trực tiếp trên bảng migration/version hiện hành của Developer Portal).
> - **Cảnh báo lỗi:** Việc gửi một phiên bản đã sunset có thể khiến request bị từ chối; mã lỗi cụ thể phải được xử lý theo response thực tế và migration notice của LinkedIn, không hard-code một mã duy nhất.
> - **Phiên bản áp dụng chuẩn hóa:** Tài liệu dùng **`202610`** làm giá trị ví dụ tại thời điểm cập nhật. Phải xác nhận lại version đang được LinkedIn cấp và ngày sunset trong bảng version/migration hiện hành trước khi triển khai; không suy ra vòng đời từ giá trị `YYYYMM` hoặc hard-code ngày sunset trong client.
> - **Quy ước triển khai:** Trong mã nguồn Kotlin của Mảng B, version này cần được định nghĩa thành hằng số tập trung (ví dụ: `private const val LINKEDIN_API_VERSION = "202610"`) để dễ bảo trì và nâng cấp định kỳ.

1. **Lấy thông tin người dùng / URN thành viên theo từng App (F1):**
   - **Đối với App Member:** Gọi OpenID Connect userinfo endpoint với token của App Member:
     ```http
     GET https://api.linkedin.com/v2/userinfo
     Authorization: Bearer {member-access-token}
     ```
     *Nhận về:* Trường `sub` mang URN cá nhân của member theo định dạng `urn:li:person:{id}` [LinkedIn Sign In with LinkedIn using OpenID Connect](https://learn.microsoft.com/en-us/linkedin/consumer/integrations/self-serve/sign-in-with-linkedin-v2).
   - **Đối với App Organization:** Do app Org chỉ tích hợp Community Management API và không có OIDC/openid, client **không gọi `/v2/userinfo`**. Thay vào đó, URN cá nhân của quản trị viên được lấy trực tiếp từ trường `roleAssignee` trong response `GET /rest/organizationAcls` ở bước dưới đây [LinkedIn Organization Access Control by Role](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/organizations/organization-access-control-by-role).

   **Lấy danh sách ACL và các Organization mà member có vai trò quản trị (F1 — App Org):**
   *(Scope yêu cầu: `r_organization_admin` là đủ cho thao tác đọc danh sách ACL qua GET; `rw_organization_admin` chỉ cần nếu thao tác ghi/sửa vai trò (riêng endpoint thống kê bài viết Share Statistics và Follower Statistics quy định cứng `rw_organization_admin`, không áp dụng cho `networkSizes`). Cần role `ADMINISTRATOR` hoặc `CONTENT_ADMINISTRATOR` (Posts API dùng `CONTENT_ADMIN`) và trạng thái `APPROVED` cho các Organization API yêu cầu quản trị [LinkedIn Organization Access Control by Role](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/organizations/organization-access-control-by-role), [LinkedIn Posts API](https://learn.microsoft.com/en-us/linkedin/marketing/integrations/community-management/shares/posts-api)).*
   ```http
   GET https://api.linkedin.com/rest/organizationAcls?q=roleAssignee&role=ADMINISTRATOR&state=APPROVED
   Headers:
     Authorization: Bearer {access-token}
     Linkedin-Version: 202610
     X-Restli-Protocol-Version: 2.0.0
   ```
   *Nhận về:* danh sách ACL chứa Organization URN, role, state và `roleAssignee`.
   - **Cơ chế finder `q=roleAssignee`:** Khi truy vấn với `q=roleAssignee&role=ADMINISTRATOR&state=APPROVED`, máy chủ LinkedIn tự động lọc theo danh tính member gắn với access token hiện tại. Client **không cần phải lọc lại theo member URN**. (Lưu ý: Không thêm tham số query `roleAssignee=urn:li:person:{person-id}` vì endpoint không hỗ trợ tham số này trên query string).
   - **Phân biệt vai trò của `organizationAcls` và `organizationAuthorizations`:**
     - **Lấy danh sách Organization mà user quản trị:** Endpoint chuẩn theo tài liệu là `GET /rest/organizationAcls?q=roleAssignee&role=ADMINISTRATOR&state=APPROVED` [LinkedIn Organization Access Control by Role](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/organizations/organization-access-control-by-role). Đây là endpoint cung cấp danh sách tất cả các Organization URN gắn với vai trò của user để hiển thị và lưu vào Room DB.
     - **Kiểm tra quyền hạn chi tiết theo hành vi (`organizationAuthorizations`):** Endpoint `GET /v2/organizationAuthorizations/(organization:{org-urn},roleAssignee:{person-urn})` [LinkedIn Getting Started with Organization Authorizations](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/organizations/organization-authorizations/getting-started) được LinkedIn thiết kế riêng cho việc kiểm tra quyền hạn chi tiết theo từng hành vi cụ thể (permission-based authorization check, ví dụ: kiểm tra quyền `ORGANIC_SHARE_CREATE` để đăng bài, `ORGANIZATION_ADMIN_COMMENT_CREATE` để trả lời bình luận, hoặc `ANALYTIC_READ` để xem số liệu). Endpoint này **không dùng để lấy danh sách Page mà user đang quản lý**, mà dùng để thẩm định quyền thực thi tác vụ trên một Organization cụ thể đã biết.
   - **Lưu Organization vào Room:** Sau khi nhận danh sách từ `organizationAcls`, trích xuất `{org-id}` từ `urn:li:organization:{org-id}`, rồi lưu Organization như một `ChannelAccount` riêng trong Room. `accountId` của account này là Organization ID/URN ổn định, không dùng `urn:li:person:{id}` thay thế.

   Các Organization URN thu được ở bước này là đầu vào cho các endpoint F5, F6 và F7 bên dưới:
   `author`/`owner` khi đăng hoặc upload ảnh, `targetUrn` khi đọc/trả lời comment,
   `organizationalEntity` khi lấy metrics và `orgUrn` khi lấy follower count.

   > **Lưu ý quyền:** `organizationAcls` chỉ cho biết các Organization và role mà access token có
   > thể truy vấn (hoặc dùng `organizationAuthorizations` để kiểm tra chi tiết quyền theo hành vi như `ORGANIC_SHARE_CREATE`);
   > từng thao tác vẫn phải kiểm tra thêm capability, sản phẩm Community Management
   > và quyền cụ thể của Organization. Không suy ra rằng có ACL là chắc chắn được đăng bài hoặc
   > đọc comment.

   > **Ghi chú lấy tên và logo cho UI F1:** Response mặc định của `organizationAcls` không nên được
   > coi là nguồn đầy đủ cho `localizedName` hoặc logo. Có thể dùng projection được tài liệu
   > `organizationAcls` hỗ trợ hoặc Organizations lookup **chỉ sau khi xác nhận đúng resource path,
   > quyền và version trong Developer Portal/tài liệu LinkedIn hiện hành**. Không hard-code
   > `/rest/organizations/{org-id}` hoặc batch `List(...)` nếu chưa có xác nhận đó.

2. **Khởi tạo và upload ảnh (F5 — Quy trình theo tài liệu [LinkedIn Images API](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/shares/images-api?view=li-lms-2026-10), ⚠️ Chưa xác minh (cần thử trên Portal/API thực tế)):**
   *(Tài liệu tham chiếu chính thức: [LinkedIn Images API](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/shares/images-api?view=li-lms-2026-10), kết hợp với [LinkedIn MultiImage API](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/shares/multiimage-post-api#create-multiimage-content) và [LinkedIn Posts API](https://learn.microsoft.com/en-us/linkedin/marketing/integrations/community-management/shares/posts-api) để nhận Image URN `urn:li:image:{id}`. Quy trình gồm 3 bước: `initializeUpload` để nhận `uploadUrl`, PUT binary ảnh, và kiểm tra trạng thái `AVAILABLE`. Lưu ý: Quyền truy cập cụ thể và hành vi thực tế của App ID vẫn cần được kiểm chứng trên môi trường dev):*
   - **Bước 1: Khởi tạo upload ảnh (`action=initializeUpload` — [LinkedIn Images API](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/shares/images-api?view=li-lms-2026-10)):**
     ```http
     POST https://api.linkedin.com/rest/images?action=initializeUpload
     Headers:
       Authorization: Bearer {access-token}
       Linkedin-Version: 202610
       X-Restli-Protocol-Version: 2.0.0
       Content-Type: application/json

     {
       "initializeUploadRequest": {
         "owner": "urn:li:organization:{org-id}"
       }
     }
     ```
     *Nhận về (response chuẩn có cấu trúc bọc trường `value`):*
     ```json
     {
       "value": {
         "uploadUrlExpiresAt": 1700000000000,
         "uploadUrl": "https://api.linkedin.com/mediaUpload/...",
         "image": "urn:li:image:{image-id}"
       }
     }
     ```
     Lưu giá trị `value.image` (Image URN) và `value.uploadUrl`.
   - **Bước 2: Tải file binary ảnh lên `uploadUrl`:**
     Gọi HTTP `PUT` gửi trực tiếp luồng binary của ảnh lên `uploadUrl` nhận được từ Bước 1 (`Content-Type: image/jpeg` hoặc `image/png`).
   - **Bước 3: Chờ ảnh sẵn sàng (`status == AVAILABLE`):**
     Hệ thống xử lý hình ảnh của LinkedIn hoạt động bất đồng bộ. Sau khi PUT binary thành công, client cần kiểm tra trạng thái ảnh trước khi đính kèm URN vào bài đăng:
     ```http
     GET https://api.linkedin.com/rest/images/{encodedImageUrn}
     Headers:
       Authorization: Bearer {access-token}
       Linkedin-Version: 202610
       X-Restli-Protocol-Version: 2.0.0
     ```
     *Đợi đến khi nhận được:* `{"status": "AVAILABLE"}` (áp dụng cơ chế polling tối đa 3–5 lần với khoảng cách 1–2 giây giữa các lần). Nếu sau 5 lần thử mà ảnh vẫn chưa sẵn sàng hoặc gặp lỗi xử lý, client không chặn luồng Worker bằng vòng lặp vô hạn mà trả về `PublishResult.RetryableError` để WorkManager lên lịch retry với exponential backoff. Tuyệt đối **không dùng `BATCH_GET`** (`ids=List(...)`) vì phương thức này bị cấm hoàn toàn ở Community Management Development Tier (B10, C7). Nếu gọi `POST /rest/posts` khi ảnh chưa chuyển sang `AVAILABLE`, LinkedIn sẽ từ chối tạo bài viết.
3. **Đăng bài viết kèm ảnh đơn (F5 — Idempotency phía App & Cú pháp Mention / Little Text Format):**
   ```http
   POST https://api.linkedin.com/rest/posts
   Headers:
     Authorization: Bearer {access-token}
     Linkedin-Version: 202610
     X-Restli-Protocol-Version: 2.0.0
     Content-Type: application/json

   {
     "author": "urn:li:organization:{org-id}",
     "commentary": "Chúc mừng @[SocialAgent Team](urn:li:organization:{org-id}) đã hoàn thành xuất sắc dự án! #SocialAgent #AI",
     "visibility": "PUBLIC",
     "distribution": {
       "feedDistribution": "MAIN_FEED",
       "targetEntities": [],
       "thirdPartyDistributionChannels": []
     },
     "content": {
       "media": {
         "id": "urn:li:image:{image-id}",
         "altText": "Mô tả ảnh sinh bởi AI"
       }
     },
     "lifecycleState": "PUBLISHED"
   }
   ```
   > [!IMPORTANT]
   > **Quy tắc Little Text Format & Bắt buộc Escape 15 Ký tự Đặc biệt khi viết Plaintext (Crucial Pitfall):**
   > - Theo tài liệu chính thức [Little Text Format](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/shares/little-text-format), trường `commentary` của Posts API sử dụng định dạng Little Text Format.
   > - **Bắt buộc escape 15 ký tự đặc biệt bằng dấu gạch chéo ngược `\` khi muốn hiển thị dưới dạng văn bản thô (plaintext) (theo [LinkedIn Little Text Format](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/shares/little-text-format#text)):** `|`, `{`, `}`, `@`, `[`, `]`, `(`, `)`, `<`, `>`, `#`, `\`, `*`, `_`, `~`.
   > - **Phạm vi áp dụng:** Theo tài liệu [LinkedIn Little Text Format](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/shares/little-text-format), mọi ký tự dành riêng phải được escape bằng dấu gạch chéo ngược `\` để LinkedIn đối xử như văn bản thô (plaintext), ngay cả khi bài viết không sử dụng mention hay template. Nếu không escape, các ký tự đặc biệt này (như dấu ngoặc, gạch đứng, ký tự markdown) có thể bị trình phân tích cú pháp của LinkedIn hiểu nhầm là cấu trúc thẻ cú pháp, dẫn tới hiển thị sai lệch hoặc lỗi.
   > - **Lưu ý triển khai phía Mảng B (Quy chuẩn Hashtag & Unit Test — C3):** Client Android trước khi gửi request cần có hàm tiện ích tiền xử lý `escapeLittleText(text: String)` để tự động chèn `\` trước 15 ký tự đặc biệt khi chúng xuất hiện dưới dạng văn bản thô do AI sinh ra (đặc biệt là dấu ngoặc `()`, `[]`, `{}`, gạch đứng `|`, markdown `*`, `_`, `~`, và `#`). Đối với hashtag: Little Text Format yêu cầu biểu diễn hashtag có cấu trúc qua cú pháp template `{hashtag|\#|tag}` (khi đó `#` bên trong template bắt buộc phải có `\`), còn nếu ký tự `#` xuất hiện độc lập trong văn bản thuần túy thì cũng phải được escape thành `\#`. Bổ sung bộ unit test xác định cho `escapeLittleText` với các chuỗi mẫu phức tạp do LLM sinh ra.
   >
   > **Cú pháp Mention chính thức trong Little Text Format:**
   > - **Cú pháp:** `@[Tên hiển thị](urn:li:organization:{id})` hoặc `@[Tên hiển thị](urn:li:person:{id})`. Tên hiển thị bên trong `@[...]` phải khớp chính xác với tên thực tế của đối tượng.
   > - **Rào cản thực tế:** Trong phạm vi các API được cấp và tài liệu chính thức hiện tại của LinkedIn, LinkedIn không cung cấp API tìm kiếm tự do để lấy URN của người dùng/tổ chức. Ứng dụng chỉ mention được khi đã nắm giữ URN hợp lệ (ví dụ Organization Page mà người dùng quản trị). Nếu không có URN, hệ thống kích hoạt fallback về **`HANDED_OFF`**.
   >
   > *Lưu ý về Idempotency & Đối soát (Reconciliation):* LinkedIn Posts API không có header idempotency native chung, và hoàn toàn không có cơ chế chặn trùng nội dung (kể cả cơ chế heuristic như mã 506 của Meta). Việc chống đăng trùng áp dụng cùng giao thức đối soát chặt chẽ qua Room DB:
   > - Ghi trạng thái `PUBLISHING` kèm `attemptStartedAt` vào Room DB **trước** khi gọi `POST /rest/posts`.
   > - Nếu gặp timeout hoặc mất kết nối sau khi đã gửi dữ liệu (`outcomeUnknown = true`), Worker khi chạy lại **không được đăng lại ngay**, mà phải thực hiện đối soát: gọi `GET https://api.linkedin.com/rest/posts?author={encodedOrgUrn}&q=author&count=10&sortBy=LAST_MODIFIED` để tra cứu các bài đăng mới nhất của author, so khớp `commentary` và thời gian tạo. Nếu tìm thấy bài đã xuất hiện trên LinkedIn, lưu URN từ kết quả và hoàn tất tác vụ; chỉ đăng lại khi đối soát xác nhận bài chưa từng được tạo trên máy chủ LinkedIn.

4. **Đăng bài viết đa ảnh (Multi-image Post — F5):**
   - **Quy trình tải lên:** Khởi tạo `initializeUpload` và tải dữ liệu nhị phân lên `uploadUrl` cho từng ảnh (tương tự quy trình ảnh đơn — ⚠️ **Cần kiểm thử thực tế**; tài liệu chính thức [LinkedIn MultiImage API](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/shares/multiimage-post-api) chỉ dẫn chiếu Images API mà không kèm file đặc tả trực tiếp) để thu thập danh sách Media URN: `[urn:li:image:{id1}, urn:li:image:{id2}, ...]`.
   - **Đăng bài bằng thuộc tính `multiImage`:**
     ```http
     POST https://api.linkedin.com/rest/posts
     Headers:
       Authorization: Bearer {access-token}
       Linkedin-Version: 202610
       X-Restli-Protocol-Version: 2.0.0
       Content-Type: application/json

     {
       "author": "urn:li:organization:{org-id}",
       "commentary": "Nội dung bài viết kèm nhiều hình ảnh...",
       "visibility": "PUBLIC",
       "distribution": {
         "feedDistribution": "MAIN_FEED",
         "targetEntities": [],
         "thirdPartyDistributionChannels": []
       },
       "content": {
         "multiImage": {
           "images": [
             { "id": "urn:li:image:{image-id-1}", "altText": "Mô tả ảnh 1" },
             { "id": "urn:li:image:{image-id-2}", "altText": "Mô tả ảnh 2" }
           ]
         }
       },
       "lifecycleState": "PUBLISHED"
     }
     ```
   - *Giới hạn kỹ thuật & Ràng buộc (theo tài liệu chính thức [Multi-image Post API](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/shares/multiimage-post-api)):*
     - **Số lượng ảnh & Loại bài viết:** Tối thiểu **2** và tối đa **20** ảnh cho một MultiImage post. Mỗi ảnh phải thuộc quyền sở hữu của author hoặc Organization; tài liệu chỉ hỗ trợ tạo bài viết tự nhiên (non-sponsored organic posts) [LinkedIn MultiImage API](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/shares/multiimage-post-api).
     - **Độ phân giải, Định dạng & Giới hạn khung hình:** Tài liệu Multi-image Post API quy định tổng độ phân giải **dưới 36.152.320 pixel** (chiều rộng $\times$ chiều cao); định dạng hỗ trợ gồm JPG, GIF, PNG. Với ảnh GIF động, giới hạn tối đa là **250 frames** [LinkedIn MultiImage API](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/shares/multiimage-post-api). `altText` là tùy chọn với độ dài tối đa **4.086 ký tự** (khuyến nghị dưới 120 ký tự) [LinkedIn MultiImage API](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/shares/multiimage-post-api).
     - **Dung lượng file:** Mức trần dung lượng không được công bố trong tài liệu Multi-image Post API (trạng thái: ⚠️ **Chưa xác minh (cần thử trên Portal/API thực tế)**; mức trần dưới 8 MB - 10 MB là khuyến nghị thực nghiệm của nhóm phát triển để đảm bảo hiệu năng tải lên từ thiết bị di động, không phải thông số của tài liệu API) [LinkedIn MultiImage API](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/shares/multiimage-post-api).
     - *Phạm vi Milestone 1:* Model `PostDraft` hiện ưu tiên hỗ trợ `imageUri` (ảnh đơn), cấu trúc `multiImage` được chuẩn hóa sẵn sàng cho giai đoạn mở rộng tiếp theo.

5. **Lấy danh sách comment (F6):**
   *(Theo tài liệu Comments API hiện hành, dùng `r_organization_social_feed`; khi chọn version khác,
   phải đối chiếu lại trang endpoint và Developer Portal, không tự đổi tên scope.)*
   ```http
   GET https://api.linkedin.com/rest/socialActions/urn%3Ali%3Ashare%3A{share-id}/comments?start=0&count=20
   Headers:
     Authorization: Bearer {access-token}
     Linkedin-Version: 202610
     X-Restli-Protocol-Version: 2.0.0
   ```
   *Lưu ý về `{targetUrn}`, Phân trang & Bẫy lỗi HTTP 404:*
   - **Xác định `{targetUrn}`:** Với thread cấp một, đây là URN của bài viết gốc (`remotePostId`, ví dụ: `urn:li:share:{share-id}` hoặc `urn:li:ugcPost:{ugcPost-id}`). Khi đọc replies của một comment, Social Actions API cho phép dùng chính `commentUrn` làm target trên path.
   - **Cơ chế phân trang (`start` & `count` — ⚠️ Chưa xác minh (cần thử trên Portal/API thực tế)):** Tài liệu chính thức [LinkedIn Comments API](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/shares/comments-api) không có bảng Query Parameters riêng cho endpoint đọc comment này; hai tham số phân trang query `start` (vị trí offset bắt đầu từ `0`) và `count` (số lượng phần tử mỗi trang) là quy ước chuẩn Rest.li xuất hiện trong các tài liệu Social Actions liên quan cũ hơn [LinkedIn Network Update Social Actions](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/shares/network-update-social-actions). Cần kiểm thử thực tế trên response thật để xác định xem phiên bản `202610` có chấp nhận hai tham số này hay sử dụng cơ chế phân trang khác.
   - **Bẫy lỗi kỹ thuật 404 khi bài viết chưa có comment:** Theo tài liệu Network Update Social Actions của LinkedIn [LinkedIn Network Update Social Actions](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/shares/network-update-social-actions), nếu bài viết chưa từng có bình luận hay tương tác nào, yêu cầu lấy danh sách comments có thể trả về lỗi **`HTTP 404 Not Found`** (*"If no comments or likes are available for a share, a request to fetch comments or shares returns 404 - Not Found"*) thay vì một mảng rỗng `elements: []`. Lớp Client/Repository của app phải bắt và xử lý trường hợp HTTP 404 này như một danh sách rỗng hợp lệ thay vì báo lỗi thất bại.
   > **Quy tắc URL Encoding của Rest.li 2.0.0 (với OkHttp):** 
   > - Trong tài liệu chính thức của LinkedIn, endpoint path xuất hiện cả hai dạng ví dụ: dạng đã percent-encode (`urn%3Ali%3AugcPost%3A...`) và dạng URN thô (`/socialActions/urn:li:comment:(urn:li:activity:...)/comments`).
   > - **Lưu ý kỹ thuật về OkHttp (phiên bản `5.4.0` trong `gradle/libs.versions.toml`):** Hàm `HttpUrl.Builder.addPathSegment(rawUrn)` tuân theo RFC 3986 path segment nên **không tự động encode các dấu `:`, `(`, `)`, `,`**. Nếu truyền URN đã encode sẵn vào `addPathSegment`, OkHttp sẽ encode dấu `%` thành `%25` gây lỗi double-encoding (`urn%253Ali...`).
   > - **Khuyến nghị thực thi:** Để đảm bảo tính tương thích cao nhất với Rest.li 2.0.0, khuyến nghị phía app tự percent-encode riêng chuỗi URN đúng một lần (chuyển đổi `:`, `(`, `)`, `,` thành `%3A`, `%28`, `%29`, `%2C`), sau đó truyền vào bằng hàm **`HttpUrl.Builder.addEncodedPathSegment(encodedUrn)`** để OkHttp giữ nguyên mã đã encode. Trong quá trình dev, nhóm nên kiểm thử thực tế cả hai dạng (dạng thô và dạng đã encode) với backend LinkedIn để xác nhận khả năng chấp nhận của server.
   > - **Phân định rõ với Body:** Tuyệt đối **KHÔNG percent-encode URN trong JSON body** (các trường `owner`, `author`, `actor`, `object`, `parentComment`... giữ nguyên định dạng chuỗi URN thô).

6. **Trả lời comment (Nested Reply — F6):**
   *(Theo tài liệu Comments API hiện hành, dùng `w_organization_social_feed`; khi chọn version khác,
   phải đối chiếu lại trang endpoint và Developer Portal, không tự đổi tên scope.)*
   ```http
   POST https://api.linkedin.com/rest/socialActions/{parentCommentUrn}/comments
   Headers:
     Authorization: Bearer {access-token}
     Linkedin-Version: 202610
     X-Restli-Protocol-Version: 2.0.0
     Content-Type: application/json

   {
     "actor": "urn:li:organization:{org-id}",
     "object": "{rootPostUrn}",
     "message": {
       "text": "Cảm ơn sự đóng góp của bạn!"
     },
     "parentComment": "{parentCommentUrn}"
   }
   ```
   *Lưu ý kỹ thuật & Hướng dẫn triển khai `SocialChannelClient.reply(commentId, text)` để tránh lỗi nhầm lẫn URN:*
   - **Phân định rõ `{parentCommentUrn}` trên URL path & `"object": "{rootPostUrn}"` trong JSON body:**
     - **Trên URL path (`{parentCommentUrn}`):** Với thao tác trả lời một bình luận (Nested Reply), endpoint của LinkedIn Social Actions API yêu cầu định danh trên path là URN của chính comment cha được trả lời (`parentCommentUrn`, ví dụ: `urn:li:comment:(urn:li:activity:{id},{sub-id})`).
     - **Trường `"object"` trong body (`{rootPostUrn}`):** Bắt buộc phải là **thread URN của bài viết gốc (Root Post / Share / UGC Post / Activity)** chứa comment đó. **Tuyệt đối không chép nhầm `commentUrn` vào trường `"object"`**; nếu truyền nhầm URN của comment vào `"object"`, API có thể trả về lỗi validation hoặc từ chối request (cần xác minh mã/thông điệp lỗi cụ thể khi kiểm thử thực tế).
     - **Trường `"parentComment"` trong body (`{parentCommentUrn}`):** Là URN của chính comment cha đang được phản hồi (ở dạng URN thô, không percent-encode).
     - *(Ghi chú với Comment cấp một vào bài viết):* Nếu là tạo bình luận cấp một trực tiếp trên bài viết (không phải nested reply), URL path là `{rootPostUrn}`, body `"object"` là `{rootPostUrn}`, và bỏ trường `"parentComment"`.
   - **Khớp nối với Interface Contract của dự án (`SocialChannelClient.reply`):**
     - Chữ ký hàm trong `core/social/SocialChannelClient.kt` chỉ nhận: `suspend fun reply(commentId: String, text: String)`. Implementation `LinkedInClient` dự kiến đặt tại `data/social/LinkedInClient.kt` (hoặc `data/linkedin/LinkedInClient.kt`) và được cung cấp qua module trong `di/`.
     - Để xác định chính xác `{rootPostUrn}` và `{parentCommentUrn}` khi thực thi cuộc gọi API này trong `LinkedInClient`:
       1. **Nguồn lấy `{rootPostUrn}`:** Khuyến nghị lấy từ trường `Comment.remotePostId` lưu trong Room DB (hoặc query entity bài viết tương ứng). Lưu ý rằng trong ví dụ chính thức của [LinkedIn Comments API](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/shares/comments-api), trường `"object"` mang giá trị Share URN (`"object": "urn:li:share:6305471423242596352"`), trong khi `parentComment` lại chứa Activity URN (`"parentComment": "urn:li:comment:(urn:li:activity:6305471423192264704,...)"`). Do đó, không nên tự suy diễn hoặc parse `threadUrn` từ chuỗi Compound URN của `parentComment` để gán vào `"object"`, vì hai URN này có thể khác loại tiền tố và định danh.
       2. **Nguồn lấy `{parentCommentUrn}`:** Sử dụng trực tiếp tham số `commentId` truyền vào hàm `SocialChannelClient.reply(commentId, text)`.
   - **Header `X-Restli-Protocol-Version: 2.0.0`:** Bắt buộc phải có để LinkedIn Rest.li framework xử lý đúng các ký tự đặc biệt (dấu ngoặc đơn, dấu phẩy) trong Compound URN của `parentComment`.
   - **Encoding của endpoint reply:** `{parentCommentUrn}` trên path khuyến nghị percent-encode riêng URN đúng một lần rồi truyền vào `HttpUrl.Builder.addEncodedPathSegment(encodedParentCommentUrn)` (và kiểm thử thực tế với cả dạng thô). Các URN trong JSON body (`actor`, `object`: `rootPostUrn`, `parentComment`: `parentCommentUrn`) giữ nguyên ở dạng URN thô hợp lệ của JSON; tuyệt đối **không percent-encode nội dung trong JSON body**.
   - **Giới hạn tần suất tạo comment ngắn hạn (1-Minute Rate Limit):** LinkedIn áp dụng giới hạn ngắn hạn theo cửa sổ 1 phút cho thao tác tạo comment của thành viên; khi vượt ngưỡng, API trả về lỗi HTTP `429` (`Comment create throttled: creation rate limit exceeded for member`) [LinkedIn Comments API](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/shares/comments-api). Tầng xử lý của app cần điều tiết khoảng nghỉ giữa các lượt gửi comment tự động để không chạm ngưỡng này.
7. **Lấy số liệu bài viết (F7):**
   *(Scope yêu cầu: `rw_organization_admin` [LinkedIn Organization Share Statistics](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/organizations/share-statistics); người dùng phải có role `ADMINISTRATOR` và Product/endpoint phải được cấp trên Developer Portal. Cần kiểm thử kèm `r_organization_social` (Cần kiểm thử thực tế) [TEST-05]).*

   > [!NOTE]
   > **Kinh nghiệm thực tế về Scope của Share Statistics API:**
   > Bảng quyền chính thức của Share Statistics API chỉ quy định scope `rw_organization_admin` kèm role `ADMINISTRATOR` [LinkedIn Organization Share Statistics](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/organizations/share-statistics). Tuy nhiên, trên thực tế cộng đồng developer ghi nhận trường hợp endpoint `organizationalEntityShareStatistics` trả về mã lỗi `HTTP 403 Forbidden` nếu access token không được cấp kèm scope `r_organization_social` (dù tài liệu [LinkedIn Organization Share Statistics](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/organizations/share-statistics) chỉ liệt kê `rw_organization_admin`). Do đó, khi cấu hình quyền cho app trên Developer Portal và xin quyền OAuth, Mảng B cần lưu ý kiểm thử thực tế sự kết hợp này trong Tuần 1 (⚠️ **Cần kiểm thử thực tế** [TEST-05]).

   Theo tài liệu chính thức về [Share Statistics API](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/organizations/share-statistics), endpoint này cung cấp hai tham số lọc riêng biệt tùy theo định dạng URN của bài viết:
   - **Trường hợp URN dạng Share (`urn:li:share:{id}`):** Dùng tham số `shares=List(...)`:
     ```http
     GET https://api.linkedin.com/rest/organizationalEntityShareStatistics?q=organizationalEntity&organizationalEntity=urn%3Ali%3Aorganization%3A{org-id}&shares=List(urn%3Ali%3Ashare%3A{share-id})
     Headers:
       Authorization: Bearer {access-token}
       Linkedin-Version: 202610
       X-Restli-Protocol-Version: 2.0.0
     ```
   - **Trường hợp URN dạng UGC Post (`urn:li:ugcPost:{id}`):** Dùng tham số chỉ mục
     `ugcPosts[0]` (và `ugcPosts[1]`, ...) theo schema hiện hành:
     ```http
     GET https://api.linkedin.com/rest/organizationalEntityShareStatistics?q=organizationalEntity&organizationalEntity=urn%3Ali%3Aorganization%3A{org-id}&ugcPosts[0]=urn%3Ali%3AugcPost%3A{ugc-post-id}
     Headers:
       Authorization: Bearer {access-token}
       Linkedin-Version: 202610
       X-Restli-Protocol-Version: 2.0.0
     ```
   > **Quy tắc encode query của Rest.li 2.0.0 (với OkHttp):** Với `shares=List(...)`, dấu `:`
   > trong từng URN phải được percent-encode; khi có nhiều share, dấu phẩy trong `List(...)`
   > cũng phải được encode theo ví dụ chính thức. Với UGC Post, dùng tham số chỉ mục
   > `ugcPosts[0]`, `ugcPosts[1]`, ... và encode giá trị URN. Khi dùng `HttpUrl.Builder`, phải
   > dùng đúng cơ chế `addEncodedQueryParameter`/tên tham số chỉ mục và encode mỗi URN đúng một
   > lần; không truyền URN đã encode vào API lần thứ hai.

   *Quy tắc triển khai & Bẫy thực tế khi parse dữ liệu (Crucial Implementation Pitfalls):*
   - **Các trường dữ liệu trả về:** `likeCount`, `commentCount`, `shareCount`, `impressionCount`, `uniqueImpressionsCount` (lượt hiển thị duy nhất), `clickCount`, `engagement`.
   - **Bẫy 1 — Bài viết chưa có tương tác không xuất hiện trong `elements`:** Nếu bài viết (`share` hoặc `ugcPost`) chưa từng có bất kỳ tương tác hoặc hiển thị nào, LinkedIn sẽ **không trả về phần tử tương ứng trong mảng `elements`** của JSON response. Phía client parser phải xử lý an toàn: nếu không tìm thấy URN trong `elements`, coi tất cả các chỉ số (`likeCount`, `commentCount`, `shareCount`, `impressionCount`, `uniqueImpressionsCount`, `clickCount`, `engagement`) có giá trị mặc định là `0`, tuyệt đối không throw ngoại lệ `NoSuchElementException`.
   - **Bẫy 2 — `likeCount` có thể mang giá trị âm:** Theo tài liệu chính thức của LinkedIn Share Statistics, số liệu `likeCount` chỉ tính các lượt like tự nhiên (organic likes). Khi một thành viên like một bài viết đang được tài trợ/quảng cáo (sponsored share) rồi sau đó bấm unlike (bỏ like), lượt like ban đầu không được tính vào organic likes nhưng thao tác unlike lại bị trừ vào tổng organic likes, dẫn đến chỉ số `likeCount` có thể mang giá trị âm. Lớp parser phía app phải bọc qua `max(0, rawLikeCount)` trước khi lưu vào Room DB hoặc hiển thị lên giao diện F7 để tránh hiển thị số âm bất thường.
   - **Bẫy 3 — Khuyến nghị gọi `socialActions` cho Like/Comment thời gian thực:** Để lấy số lượng like/reaction và comment khớp chính xác theo thời gian thực với những gì đang hiển thị trên feed bài đăng, tài liệu chính thức của LinkedIn khuyến nghị gọi trực tiếp endpoint `GET /rest/socialActions/{targetUrn}` thay vì chỉ dựa vào `organizationalEntityShareStatistics`.
   - **Phân biệt định dạng URN:** Bài viết tạo qua endpoint `POST /rest/posts` trả về định danh trong header `x-restli-id`; phải lưu nguyên giá trị response và kiểm tra loại URN thực tế trước khi gọi endpoint metrics. Share Statistics hỗ trợ tham chiếu `share` hoặc `ugcPost` theo tham số tương ứng; không tự đổi tiền tố hoặc suy diễn một định dạng duy nhất.
   - Mã nguồn Mảng B cần phân tích tiền tố của URN bài viết lưu trong Room DB để ánh xạ sang tham số tương ứng (`shares` hoặc `ugcPosts[0]`, tăng chỉ số khi truy vấn nhiều UGC Post), và phải báo lỗi rõ ràng nếu nhận URN ngoài các dạng được endpoint metrics hỗ trợ.
   - ⚠️ *Chưa xác minh (cần thử trên Portal/API thực tế):* Do `organizationalEntityShareStatistics` là endpoint thống kê kế thừa, nhóm cần gọi thử nghiệm trực tiếp trên môi trường dev với ID bài đăng vừa tạo từ `POST /rest/posts` để xác nhận định dạng URN tương thích chính xác trên phiên bản `202610`.
   - **Giới hạn dữ liệu (Rolling 12-month window):** Tài liệu chính thức [LinkedIn Organization Share Statistics](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/organizations/share-statistics?view=li-lms-2026-10) quy định rõ: endpoint `organizationalEntityShareStatistics` chỉ trả về dữ liệu share trong vòng **12 tháng gần nhất** theo cơ chế cửa sổ trượt (rolling 12-month window). Tài liệu phân biệt 2 chế độ: bỏ qua tham số `timeIntervals` để lấy số liệu tích lũy (*Lifetime share statistics*), hoặc truyền `timeIntervals` để lấy số liệu theo từng khoảng thời gian (*Time-bound share statistics*). Văn bản tài liệu không nêu bất kỳ ngoại lệ nào cho phép Lifetime statistics vượt qua giới hạn 12 tháng (⚠️ *Chưa xác minh (cần thử trên Portal/API thực tế):* Cần kiểm thử thực tế trên môi trường dev xem các bài viết tạo cách đây hơn 12 tháng có trả về số liệu khi gọi Lifetime statistics hay không; app không được tự suy diễn ngoại lệ hay giả định có thể truy vấn lịch sử ngoài cửa sổ 12 tháng).
8. **Lấy số follower (F7):**
   *(Lưu ý về quyền: Trong tài liệu [LinkedIn Organization Network Size](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/organizations/organization-network-size), mục Organization Network Size cho endpoint `networkSizes` không đặt ra bảng yêu cầu scope `rw_organization_admin` hay vai trò `ADMINISTRATOR`; yêu cầu `rw_organization_admin` và vai trò `ADMINISTRATOR` trong tài liệu tham chiếu này là dành riêng cho mục Follower Statistics bên dưới. Cần kiểm tra thực tế scope tối thiểu của token khi gọi `networkSizes` trên môi trường dev).*
   ```http
   GET https://api.linkedin.com/v2/networkSizes/urn:li:organization:{org-id}?edgeType=CompanyFollowedByMember
   Headers:
     Authorization: Bearer {access-token}
     Linkedin-Version: 202610
     X-Restli-Protocol-Version: 2.0.0
   ```
   *Trả về:* `firstDegreeSize`.
   
   *Lưu ý về endpoint và tham số `edgeType`:*
   - Tài liệu chính thức Organization Network Size API quy định base path là **`/v2/networkSizes/`** và enum là **`CompanyFollowedByMember`** [LinkedIn Organization Network Size](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/organizations/organization-network-size).
   - Mặc dù tài liệu chính thức chỉ quy định endpoint `/v2/networkSizes` và `CompanyFollowedByMember` [LinkedIn Organization Network Size](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/organizations/organization-network-size) (⚠️ Chưa xác minh (cần thử trên Portal/API thực tế) [TEST-04]), để đảm bảo tính tương thích với chuẩn versioned Rest.li hiện hành (header `Linkedin-Version: 202610`), nhóm nên kiểm thử thực tế gọi endpoint này trong Tuần 1 (⚠️ **Cần kiểm thử thực tế** [TEST-04]).

### 3.4. Giới hạn (Rate Limits) & Quy định kiểm duyệt
- **Độ dài bài viết tối đa:** Giới hạn của app là `3.000` ký tự theo cấu hình trong `ChannelType.LINKEDIN(maxChars = 3_000)`. Lưu ý: Tài liệu Posts API [LinkedIn Posts API](https://learn.microsoft.com/en-us/linkedin/marketing/integrations/community-management/shares/posts-api) chỉ trả về mã lỗi `FIELD_LENGTH_TOO_LONG` khi vượt quá độ dài tối đa mà không công bố con số ký tự cụ thể trong tài liệu chính thức; con số 3.000 ký tự là cấu hình nội bộ của app (⚠️ **Cần kiểm thử thực tế** trên API runtime).
- **Kích thước ảnh & Định dạng (theo Multi-image Post API [LinkedIn MultiImage API](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/shares/multiimage-post-api)):**
  - **Giới hạn pixel:** Tổng số pixel mỗi ảnh phải **dưới 36.152.320 pixel**. Định dạng hỗ trợ: JPG, GIF, PNG (ảnh GIF hỗ trợ tối đa 250 frames) [LinkedIn MultiImage API](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/shares/multiimage-post-api).
  - **Dung lượng file & Tỉ lệ:** Mức trần dung lượng không được công bố trong tài liệu Multi-image Post API (trạng thái: ⚠️ **Cần kiểm thử thực tế**; con số 8 MB là khuyến nghị thực tế của nhóm để tránh timeout khi tải lên di động). Tỉ lệ `1.91:1` (ngang: 1200 x 627 px) hoặc `1:1` (vuông: 1200 x 1200 px) là đề xuất thiết kế giao diện phía app (tài liệu [LinkedIn MultiImage API](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/shares/multiimage-post-api) không quy định tỉ lệ khung hình cụ thể mà chỉ giới hạn tổng pixel dưới 36.152.320).
- **Hạn ngạch & Giới hạn tần suất gọi (Rate Limits & Quotas theo Product / Tier):**
  - **Sản phẩm Share on LinkedIn (Đăng bài cá nhân):**
    - LinkedIn không công bố một quota chuẩn chung trong tài liệu cho mọi endpoint. Hạn mức application/member thực tế phải xem trên Developer Portal Analytics sau khi app gọi endpoint tương ứng.
    - Hạn mức được tính theo số request API trong cửa sổ 24 giờ và reset vào 00:00 UTC; không đồng nhất với số bài đăng vì bài có media còn phát sinh các request upload.
  - **Sản phẩm Community Management API (Page Công ty — F6/F7):**
    - Theo tài liệu chính thức về cấp quyền truy cập [Increasing Access](https://learn.microsoft.com/en-us/linkedin/marketing/increasing-access?view=li-lms-2026-10), hạn ngạch của **Community Management Development Tier** được quy định giới hạn cụ thể:
      - **Toàn bộ API:** Tối đa **500 API calls / app / 24 giờ**.
      - **Giới hạn theo Member:** Tối đa **100 API calls / member / 24 giờ**.
      - **Các API dùng `BATCH_GET`:** Hoàn toàn **không được phép gọi** (No API calls allowed).
      - **Webhook cho Social Actions:** Tính năng Push notifications bị vô hiệu hóa.
    - **Bảng dự toán ngân sách Quota (Call Budget Analysis — B10):**
      Với giới hạn cứng 100 calls/member/ngày, một nhóm gồm 4 thành viên cùng phát triển/thử nghiệm rất dễ cạn kiệt quota nếu WorkManager đồng bộ ngầm thường xuyên:

      | Thao tác nghiệp vụ | Chuỗi API thực tế | Ước tính số call tiêu tốn |
      |---|---|---|
      | **Đăng 1 bài văn bản (F5)** | `POST /rest/posts` | **1 call** |
      | **Đăng 1 bài kèm ảnh (F5)** | `initializeUpload` + 1–4 lần polling `GET /rest/images/{id}` + `POST /rest/posts` | **3–6 calls** (trung bình 4–5 calls/bài) |
      | **Đọc comment 1 bài (F6)** | `GET /rest/socialActions/{urn}/comments` | **1 call** / trang (cursor) |
      | **Lấy số liệu & follower (F7)** | `GET /rest/organizationalEntityShareStatistics` + `GET /v2/networkSizes/...` | **2 calls** / bài viết |

    - **Chiến lược điều phối hạn ngạch thông minh (Quota Management Policy):**
      1. **F7 (Đồng bộ số liệu nền):** Hàng đợi WorkManager chỉ chạy định kỳ với tần suất tối thiểu **6–12 giờ một lần** (hoặc 1 lần/ngày trong giai đoạn dev), không gọi liên tục.
      2. **F6 (Đồng bộ comment):** Chỉ đồng bộ theo nhu cầu (on-demand) khi người dùng mở màn hình chi tiết bài viết, không đồng bộ định kỳ chạy ngầm.
      3. **Xử lý khi nhận HTTP 429 Too Many Requests:** Khi nhận mã 429, app lập tức **tạm dừng (pause) toàn bộ hàng đợi của kênh LinkedIn** đến mốc reset kế tiếp (**00:00 UTC**), tránh gửi dồn request gây khóa tài khoản hoặc khóa app trên portal.
    - Chu kỳ hạn ngạch tính theo 24 giờ và reset vào 00:00 UTC. Khi cần nâng hạn mức lên Standard Tier, nhóm phải nộp đơn đăng ký xét duyệt.
  - **Cơ chế xử lý khi vượt ngưỡng:** LinkedIn có thể trả về HTTP `429 Too Many Requests`; client
    phải coi đây là lỗi tạm thời có điều kiện, áp dụng backoff có giới hạn và không mặc định rằng
    response luôn có header `Retry-After` hoặc tự suy ra thời điểm phục hồi. Chỉ map sang
    `PublishResult.RetryableError` khi response/endpoint cho thấy đúng là lỗi rate limit.
- **Theo dõi Quota & Usage thực tế theo từng endpoint trên LinkedIn Developer Portal:**
  - Theo tài liệu chính thức từ LinkedIn về [API Rate Limits](https://learn.microsoft.com/en-us/linkedin/shared/api-guide/concepts/rate-limits), LinkedIn thiết lập rate limit riêng biệt theo từng API Product, từng endpoint và cấp độ ứng dụng.
  - **Cảnh báo qua Email khi chạm 75% Quota:** Quản trị viên của ứng dụng (Developer Admins) sẽ nhận được email cảnh báo khi ứng dụng vượt quá **75%** hạn mức rate limit quota được giao (chỉ kích hoạt với vi phạm ngưỡng cấp độ application, độ trễ thông báo khoảng 1–2 giờ) [LinkedIn API Rate Limiting](https://learn.microsoft.com/en-us/linkedin/shared/api-guide/concepts/rate-limits).
  - Do đó, **tài liệu ưu tiên phương pháp đối chiếu và kiểm tra trực tiếp hạn ngạch theo từng endpoint trên tab Analytics của Developer Portal**:
    1. Đăng nhập [LinkedIn Developer Portal](https://developer.linkedin.com/), chọn ứng dụng `SocialAgent App`.
    2. Chọn tab **Analytics** trên thanh menu điều hướng của ứng dụng.
    3. Chọn xem theo bộ lọc **Application Quota** hoặc **Member Quota** để theo dõi số lượng call đã thực hiện và hạn ngạch còn lại trong ngày theo từng endpoint.
    4. **Lưu ý hiển thị của Portal:** Tab *Analytics* là nguồn quan sát usage/rate limit cho từng app/product/endpoint. Với các endpoint chưa hiển thị trong bảng rate limit, nhà phát triển có thể thực hiện 1 cuộc gọi API thử nghiệm (single test API call) để Developer Portal ghi nhận và hiển thị hạn mức tương ứng [LinkedIn API Rate Limiting](https://learn.microsoft.com/en-us/linkedin/shared/api-guide/concepts/rate-limits).
- **Quyền nào cần App Review / Phê duyệt Sản phẩm (Products):**
  - *Sản phẩm Share on LinkedIn & Sign In with OpenID Connect:* Có thể cung cấp luồng xin các scope `openid`, `profile` và `w_member_social`, nhưng không được coi sản phẩm là bằng chứng rằng mọi scope đã được cấp tự động cho mọi app. Runtime phải đọc scope/token thực tế và kiểm tra Developer Portal; nếu `w_member_social` không được cấp thì không gán capability `PUBLISH`.
  - *Sản phẩm Community Management API (Cần đăng ký & duyệt):* Chỉ ghi các scope thực sự được
    Developer Portal cấp cho app. Tên scope có thể khác giữa các trang endpoint/version (ví dụ
    `r_organization_social_feed` ở tài liệu Comments API hiện hành); không suy ra scope hợp lệ chỉ
    từ tên product hoặc từ một endpoint khác.
- **Chế độ Development dùng được tới đâu:**
  - **Phạm vi hoạt động:** Có thể kiểm thử đăng bài lên trang cá nhân của Developer/Tester và tương tác với Organization Page mà thành viên có vai trò phù hợp, nếu app đã được cấp đúng product/scope. Việc là Developer/Tester không tự cấp quyền quản trị Organization hoặc thay thế điều kiện của Community Management API. Riêng Community Management Development Tier có hạn ngạch cứng 500 calls/app/24h và 100 calls/member/24h [LinkedIn Increasing Access](https://learn.microsoft.com/en-us/linkedin/marketing/increasing-access?view=li-lms-2026-10); các endpoint/sản phẩm khác phải theo dõi và kiểm tra hạn ngạch cụ thể trên Developer Portal Analytics [LinkedIn API Rate Limiting](https://learn.microsoft.com/en-us/linkedin/shared/api-guide/concepts/rate-limits).
  - **Dự phòng rào cản kiểm duyệt:** Nếu LinkedIn chưa kịp cấp quyền Community Management API cho Organization Page, app có thể kích hoạt luồng **`HANDED_OFF`** (Mục 4) để chuyển giao cho người dùng đăng thủ công qua notification và Share Sheet foreground.

---

## 4. Chức năng không làm được qua API & Đề xuất "Đã bàn giao" (`HANDED_OFF`)

### 4.1. Các hạn chế bất khả kháng từ chính sách API
1. **Đăng bài lên Facebook cá nhân (Personal Profile Timeline):** Quyền `publish_actions` không còn được hỗ trợ trong danh mục quyền hiện hành của Pages API; Pages API chỉ hỗ trợ đăng bài lên Facebook Page (yêu cầu quyền `pages_manage_posts`) và không hỗ trợ đăng trực tiếp lên trang cá nhân người dùng [Meta Pages API - Overview](https://developers.facebook.com/documentation/pages-api/overview), [Meta Permissions Reference](https://developers.facebook.com/docs/permissions).
2. **Mention người dùng cá nhân trên Facebook & Rào cản tìm kiếm ID:**
   - *Meta (Facebook):* Mention chỉ là capability có điều kiện. App phải xác nhận endpoint/version, Page task, product và định danh Page-scoped ID được phép trước khi thử; đây không phải cơ chế tìm kiếm/mention tự do mọi hồ sơ cá nhân. Nếu điều kiện không được xác nhận hoặc request bị từ chối, app kích hoạt luồng `HANDED_OFF` để người dùng tag trực tiếp trên app Facebook.
   - *LinkedIn:* Việc mention Member/Organization bằng URN trong nội dung bài đăng **chưa được coi là capability được bảo đảm chung**; chỉ bật sau khi kiểm thử thành công với Posts API, product, version và quyền cụ thể của ứng dụng. Nếu chưa xác nhận hoặc không có URN hợp lệ, hệ thống kích hoạt luồng `HANDED_OFF` để người dùng mention trực tiếp trên ứng dụng mạng xã hội.
3. **App chưa được cấp quyền Public / Chưa qua App Review:** Người dùng ngoài danh sách Developer/Tester có thể không dùng được các permission/product yêu cầu Advanced Access hoặc App Review. Business Verification, screencast và điều kiện bổ sung chỉ áp dụng khi Meta yêu cầu cho permission/product cụ thể; không suy ra rằng mọi permission Pages đều bắt buộc cùng một điều kiện.
4. **Kênh không thể tự khắc phục ở runtime:** Nếu sau khi kiểm tra token, permission, Page task, product và endpoint mà thao tác vẫn không được cấp quyền, client không nên retry vô hạn và kích hoạt cơ chế `HANDED_OFF`. Client cần phân loại chính xác giữa lỗi phân quyền không thể tự khắc phục ở runtime (`PermissionError` $\rightarrow$ chuyển sang `HANDED_OFF`), lỗi token hết hạn/bị thu hồi (`AuthenticationError` $\rightarrow$ yêu cầu người dùng kết nối lại) và lỗi sự cố tạm thời (`TransientError` $\rightarrow$ exponential backoff retry); không tự động quy chụp mọi lỗi HTTP là lỗi vĩnh viễn (`PermanentError`) mà phải dựa trên `error.code`, `error_subcode` và message cụ thể để giữ nguyên vẹn thông tin chẩn đoán lỗi.

### 4.2. Cơ chế giải pháp "Đã bàn giao" (`HANDED_OFF`)
Khớp với `PostStatus.HANDED_OFF` trong `core/model/Post.kt` (yêu cầu F5 trong `requirements.md`):
```text
[DRAFT] (AI tạo bài, approved = false — Quy tắc 6: Chưa được duyệt)
   │
   ▼ (Người dùng duyệt bài F4: approved = true)
[QUEUED] (Đưa vào hàng đợi WorkManager hoặc lên lịch đăng)
   │
   ▼ (WorkManager bắt đầu xử lý, ghi attemptStartedAt vào Room)
[PUBLISHING]
   ├──▶ (API thành công) ───────────▶ [PUBLISHED] (Lưu remotePostId, kết thúc F5)
   ├──▶ (Lỗi quyền / Thiếu API) ────▶ [HANDED_OFF] (Worker bắn Notification ──▶ UI mở Share Sheet)
   └──▶ (Lỗi vĩnh viễn / Nội dung) ─▶ [FAILED] (Báo lỗi cho người dùng)
```
Thay vì xem trường hợp không gọi được API là lỗi thất bại (`PostStatus.FAILED`), hệ thống chuyển sang luồng **Bàn giao quyền đăng bài cho người dùng** thông qua Android OS native capabilities.

#### Rào cản kỹ thuật của luồng chạy nền (Background Execution Restrictions)
Hàng đợi đăng bài chạy trong `WorkManager` (chạy ngầm ở background). Theo quy định bảo mật và kiến trúc của hệ điều hành Android:
- **Chặn mở Activity từ background (Android 10+ / API 29+):** Android cấm tuyệt đối các tiến trình nền tự ý khởi chạy Activity (`startActivity`) khi ứng dụng không có Activity hiển thị ở foreground. Lệnh gọi `startActivity(chooser)` trực tiếp từ Worker sẽ bị hệ thống chặn hoàn toàn.
- **Lỗi Context không phải Activity:** Gọi `startActivity` từ context của Worker/Application mà thiếu flag `Intent.FLAG_ACTIVITY_NEW_TASK` sẽ gây sập ứng dụng (`AndroidRuntimeException: Calling startActivity() from outside of an Activity context requires the FLAG_ACTIVITY_NEW_TASK flag`).
- **Trải nghiệm Clipboard ngầm kém (Android 13+ / API 33+):** Tự động ghi vào `ClipboardManager` trong background khi người dùng không tương tác trực tiếp là hành vi xấu về UX, và kể từ Android 13 hệ thống sẽ hiển thị Clipboard Overlay preview bất ngờ gây khó chịu cho người dùng.

Do đó, luồng `HANDED_OFF` bắt buộc phải được thiết kế phân tách thành **hai pha độc lập**:

#### Pha 1: Xử lý nền trong Worker (Background Worker Phase)
Khi Worker phát hiện lỗi phân quyền không thể tự khắc phục ở runtime (hoặc thiếu capability bắt buộc):
1. **Cập nhật Room DB:** Đổi trạng thái bài viết sang `PostStatus.HANDED_OFF` trong Room Database, lưu kèm nguyên nhân lỗi (ví dụ: *"Thiếu quyền pages_manage_posts"* hoặc *"Chưa cấp Community Management API"*).
2. **Đăng thông báo hệ thống (Notification):** Phát một Notification tới khay thông báo của thiết bị với tiêu đề *"Bài viết cần đăng thủ công"* và nội dung tóm tắt; đính kèm `PendingIntent` trỏ trực tiếp tới màn hình chi tiết bài viết (`PostDetailFragment`).
   - *Lưu ý quyền Notification:* Trên Android 13+ (API 33+), việc hiển thị notification cần người dùng cấp quyền `POST_NOTIFICATIONS`. Nếu người dùng từ chối quyền, notification không hiển thị nhưng bài viết vẫn được lưu trạng thái `HANDED_OFF` trong Room và hiển thị badge tương ứng khi mở danh sách bài.
3. **Quy tắc bất biến:** Worker **tuyệt đối không** gọi `ClipboardManager` và **không** gọi mở Share Sheet / Activity.

#### Pha 2: Xử lý tiền cảnh khi người dùng tương tác (Foreground Fragment Phase)
Khi người dùng bấm vào Notification hoặc chủ động mở màn hình chi tiết bài viết đang ở trạng thái `HANDED_OFF`:
1. Fragment hiển thị rõ trạng thái bàn giao và cung cấp nút bấm hành động nổi bật: **"Đăng thủ công"** (Manual Share).
2. **Chỉ khi người dùng chủ động nhấn nút** (explicit user click) khi Fragment đang ở foreground, ứng dụng mới thực thi:
   ```kotlin
   // Thực thi trong Fragment đang active ở foreground
   val clipboard = requireContext().getSystemService(ClipboardManager::class.java)
   val clip = ClipData.newPlainText(getString(R.string.handoff_clip_label), draft.text)
   clipboard?.setPrimaryClip(clip)

   val shareIntent = Intent(Intent.ACTION_SEND).apply {
       type = if (draft.imageUri != null) "image/*" else "text/plain"
       putExtra(Intent.EXTRA_TEXT, draft.text)
       draft.imageUri?.let { uriStr ->
           // uriStr là content:// URI do FileProvider cấp
           putExtra(Intent.EXTRA_STREAM, Uri.parse(uriStr))
       }
       addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
   }
   val chooser = Intent.createChooser(shareIntent, getString(R.string.share_handoff_title))
   startActivity(chooser)
   ```
   - **Xử lý URI ảnh an toàn:** `draft.imageUri` phải là `content://` URI do `FileProvider.getUriForFile(...)` sinh ra; tuyệt đối không truyền trực tiếp `file://` hoặc đường dẫn raw (`/data/user/0/...`) để tránh `FileUriExposedException`.
   - **Hỗ trợ bài viết đa ảnh:** Sử dụng `Intent.ACTION_SEND_MULTIPLE` kết hợp `putParcelableArrayListExtra(Intent.EXTRA_STREAM, imageUris)`.
   - **Tối ưu UX overlay (Android 13+):** Hệ thống Android 13+ tự hiển thị preview clipboard khi ghi dữ liệu. Không mở Dialog hay Snackbar che khuất Share Sheet native.

#### Hạn chế thực tế của nền tảng khi chuyển sang Facebook & LinkedIn
- **Ứng dụng Facebook native bỏ qua `Intent.EXTRA_TEXT`:** Theo chính sách nền tảng của Meta (Platform Policy), app Facebook không cho phép ứng dụng bên thứ 3 điền sẵn nội dung bài viết qua Intent. Do đó, khi mở Facebook qua Share Sheet, chỉ có file ảnh được đính kèm, còn khung soạn thảo text sẽ để trống. **Vì vậy, thao tác sao chép text vào Clipboard là cơ chế cốt lõi để chuyển giao nội dung.** UI của app phải hiển thị thông báo/hướng dẫn rõ: *"Nội dung bài viết đã được sao chép vào bộ nhớ tạm. Hãy dán vào ô soạn bài trên mạng xã hội."*
- **Đăng nhầm vào Profile cá nhân thay vì Facebook Page:** Khi chia sẻ qua app Facebook native, mặc định app Facebook sẽ mở giao diện đăng lên trang cá nhân người dùng. Giao diện app cần hướng dẫn người dùng chuyển profile sang Page hoặc mở ứng dụng **Meta Business Suite** trước khi dán bài.
- **Với LinkedIn:** Ứng dụng LinkedIn trên một số phiên bản có thể nhận hoặc bỏ qua `EXTRA_TEXT`; cơ chế Clipboard bảo đảm người dùng luôn có sẵn nội dung để dán.
- **Hậu kiểm sau bàn giao:** Bài viết vẫn được lưu giữ trong Room DB. Giao diện cung cấp nút *"Tôi đã đăng xong"* để người dùng xác nhận đã hoàn tất đăng bài thủ công, giúp trạng thái bài viết và các tác vụ theo dõi F7 không bị treo.

---

## 5. Trạng thái Đăng ký App trên Developer Portals & Kiến trúc Bảo mật Secret

> [!CAUTION]
> **Nguyên tắc bảo mật sống còn (RFC 8252 — OAuth 2.0 for Native Apps):**
> Ứng dụng Android là **Public Client** (khách công khai). Mã nguồn và tài nguyên sau khi đóng gói thành file APK đều có thể bị dịch ngược (decompiled bằng `jadx`, `apktool`, lệnh `strings`). Việc đưa `client_secret` từ `local.properties` nạp vào `BuildConfig` **vẫn làm lộ bí mật trong mã bytecode của file APK**. File `.gitignore` chỉ bảo vệ bí mật không bị commit lên GitHub, hoàn toàn **KHÔNG bảo vệ được bản build APK** khi phân phối hoặc khi file APK rơi vào tay kẻ xấu.
>
> **Do đó:** Tuyệt đối **KHÔNG nhúng Client Secret / App Secret vào app Android** dưới bất kỳ hình thức nào.

### 5.1. Meta for Developers (Facebook)
- **Tên App:** `SocialAgent-Dev`
- **Loại App (App Type):** `Business`
- **Trạng thái truy cập quyền:** `Standard Access` (phục vụ tài khoản nội bộ có App Role hoặc Business Role; Business App không có toggle Development/Live mode)
- **Kiểm tra loại hình đăng nhập trên Dashboard (Facebook Login vs Login for Business):**
  - Do app thuộc loại `Business`, Meta có thể yêu cầu sử dụng **Facebook Login for Business** thay cho Facebook Login cổ điển.
  - **Cần kiểm tra trên Dashboard:**
    - Nếu app kích hoạt *Facebook Login*: Luồng OAuth sử dụng tham số `scope=...` như thông thường.
    - Nếu app kích hoạt *Facebook Login for Business*: Nhóm cần vào mục *Facebook Login for Business $\rightarrow$ Configurations*, tạo Configuration chứa các quyền cần xin và lấy **`config_id`** để nạp vào app (URL OAuth truyền `config_id` thay vì `scope`).
- **App ID & Client Token (Public Identifiers cho Meta SDK — B3):** Đã tạo và cấu hình Android Platform:
  - Package Name: `com.lttbdd.socialagent`
  - **Android Key Hash:** Bắt buộc đăng ký Key Hash cho **cả debug keystore** (phục vụ phát triển nội bộ) **lẫn release keystore** (hoặc SHA-1 fingerprint từ Google Play App Signing nếu phát hành qua Play Store). Nếu thiếu release key hash, luồng đăng nhập trên bản build release sẽ lập tức báo lỗi cấu hình.
  - **Meta Client Token (`facebook_client_token`):** Meta SDK bắt buộc khai báo cả App ID lẫn Client Token trong thẻ `<meta-data>` của `AndroidManifest.xml` (`com.facebook.sdk.ApplicationId` và `com.facebook.sdk.ClientToken`). Client Token là định danh công khai giúp SDK xác thực gói ứng dụng với máy chủ Meta.
  - **Quy chuẩn đa ngôn ngữ (AGENTS.md Quy tắc 4):** Các giá trị kỹ thuật này đặt trong `res/values/strings.xml` bắt buộc phải kèm thuộc tính `translatable="false"` (ví dụ: `<string name="facebook_app_id" translatable="false">...</string>`, `<string name="facebook_client_token" translatable="false">...</string>`) để `lintDebug` không báo lỗi thiếu bản dịch tiếng Việt trong `res/values-vi/strings.xml`.
- **Cấu hình Valid OAuth Redirect URIs (trong Facebook Login Settings):**
  - **Phương án 1 (SDK):** Không cần đăng ký Redirect URI cho web dialog vì SDK dùng cơ chế App Switch / Key Hash nội bộ.
  - **Phương án 2 (Web OAuth qua BFF):** Bắt buộc đăng ký URL HTTPS của máy chủ backend (ví dụ: `https://api.yourdomain.com/auth/facebook/callback`). Theo quan sát thực tế trên Dashboard, mục *Valid OAuth Redirect URIs* yêu cầu URL dạng `https://` và chưa có tài liệu chính thức nào của Meta xác nhận cho phép lưu custom scheme (`com.lttbdd.socialagent://...`) cho luồng Web OAuth.
- **Cơ chế xác thực không dùng App Secret (Đối chiếu kiến trúc §2.1):**
  - **Phương án 1 (Meta Android SDK — Khuyến nghị):** Meta xác thực danh tính ứng dụng thông qua bộ ba **App ID + Package Name + Android Key Hash**. Cơ chế này do SDK đảm nhiệm, không cần `app_secret` trong ứng dụng.
  - **Phương án 2 (Web OAuth qua AppAuth / Custom Tabs):**
    - ⚠️ **Trạng thái: Chưa xác minh (cần thử trên Portal/API thực tế) (thử nghiệm)**. Việc dùng Custom Scheme trực tiếp làm `redirect_uri` hoặc trao đổi token bằng PKCE (`code_verifier`) không secret chưa có căn cứ chính thức từ tài liệu Graph API cho ứng dụng di động thông thường.
    - Android Key Hash đã cấu hình trên Portal **chỉ áp dụng cho Phương án 1 (SDK), không áp dụng cho Phương án 2 (Web OAuth)**.
    - Nếu sử dụng Phương án 2, luồng triển khai chuẩn là Meta redirect HTTPS về máy chủ BFF; server BFF giữ bí mật `app_secret` để đổi mã ủy quyền lấy Long-lived Token và cấp mã trao đổi dùng một lần (`exchange_code`) về app Android qua back-channel; file APK tuyệt đối không nhúng `app_secret`.
  - **Kiểm tra Token an toàn:** App kiểm tra token trực tiếp qua endpoint dữ liệu `GET /v26.0/me`, tránh phát sinh thêm network round-trip và token phụ khi gọi endpoint quản trị `/debug_token`.
- **Thành viên có quyền thử nghiệm:** Thêm tài khoản Facebook của 4 thành viên nhóm vào danh sách **Developers / Testers** trong mục *App Roles*.

### 5.2. LinkedIn Developers (Tách thành 2 App Độc lập)
Theo tài liệu chính thức của LinkedIn ([LinkedIn API Partner Support Guide](https://learn.microsoft.com/en-us/linkedin/shared/linkedin-api-partner-support-guide), [LinkedIn Community Management Overview](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/community-management-overview)), *Community Management API* bắt buộc phải là sản phẩm duy nhất trên developer app. Nếu app đã bật sản phẩm khác (như OIDC hay Share on LinkedIn), nút đăng ký Community Management API sẽ bị khóa (màu xám). Do đó, kế hoạch dùng một App ID duy nhất cho cả Member lẫn Organization là không khả thi; nhóm phân tách thành 2 ứng dụng độc lập trên Developer Portal:

1. **App Member (Trang cá nhân — App hiện tại `SocialAgent App`):**
   - **Tên App:** `SocialAgent App`
   - **Trạng thái:** `Development`
   - **Sản phẩm kích hoạt:** `Sign In with LinkedIn using OpenID Connect` (cho F1) và `Share on LinkedIn` (cho F5 đăng bài cá nhân). Tuyệt đối **không** đăng ký thêm Community Management API trên app này.
   - **Client ID:** `LINKEDIN_MEMBER_CLIENT_ID` (Public Identifier).
   - **Giao thức Native PKCE:** Gửi ticket yêu cầu bật cờ Native PKCE cho App ID này; đăng ký redirect loopback `http://127.0.0.1:{port}/callback`.

2. **App Organization (Trang công ty — Tạo mới, gắn cùng Company Page):**
   - **Tên App:** `SocialAgent Org` (gắn kết cùng Company Page của nhóm).
   - **Trạng thái:** `Development`
   - **Sản phẩm kích hoạt:** **Duy nhất** `Community Management API` (Development Tier).
   - **Mục đích:** Phục vụ F1 đọc danh sách Organization (`organizationAcls`), F5 đăng bài Organization (`w_organization_social`), F6 comment và F7 metrics. App không có sản phẩm OIDC; URN người dùng quản trị được lấy từ trường `roleAssignee` trong response `organizationAcls`.
   - **Client ID:** `LINKEDIN_ORG_CLIENT_ID` (Public Identifier).
   - **Giao thức Native PKCE:** Gửi ticket yêu cầu bật cờ Native PKCE riêng cho App ID này; đăng ký redirect loopback `http://127.0.0.1:{port}/callback`.
   - **Redirect qua BFF (nếu dùng phương án phụ):** Dùng URL HTTPS trỏ về backend/BFF (`https://api.yourdomain.com/auth/linkedin/callback`).

### 5.3. Hướng dẫn Cấu hình Định danh Cục bộ (Public Client IDs Configuration)
Thay vì lưu `client_secret`, ứng dụng chỉ quản lý các **Public Client IDs**:

1. **Khai báo trong `local.properties` (hoặc cấu hình qua biến môi trường / Gradle properties):**
   ```properties
   # Public Client Identifiers (Không phải bí mật, nhưng tách ra để linh hoạt môi trường dev/staging)
   FACEBOOK_APP_ID=your_facebook_app_id_here
   FACEBOOK_CLIENT_TOKEN=your_facebook_client_token_here # Bổ sung cho Meta Android SDK
   FACEBOOK_CONFIG_ID=your_facebook_config_id_here       # Bổ sung nếu app yêu cầu Facebook Login for Business
   LINKEDIN_MEMBER_CLIENT_ID=your_linkedin_member_client_id_here
   LINKEDIN_ORG_CLIENT_ID=your_linkedin_org_client_id_here
   ```
2. **Cơ chế nạp vào `BuildConfig`:**
   - Khi triển khai F1, `app/build.gradle.kts` nạp `FACEBOOK_APP_ID`, `FACEBOOK_CLIENT_TOKEN`, `FACEBOOK_CONFIG_ID` (tùy chọn), `LINKEDIN_MEMBER_CLIENT_ID` và `LINKEDIN_ORG_CLIENT_ID` vào `BuildConfig` (`buildFeatures.buildConfig = true`).
   - Có cơ chế fallback giá trị mặc định khi chạy trên CI GitHub Actions để lệnh `./gradlew testDebugUnitTest` và `./gradlew assembleDebug` không bị lỗi compile.
3. **Bảo mật Token phía Client (`SecureTokenStore` thay thế `EncryptedSharedPreferences` — B1):**
   - **Lý do loại bỏ `EncryptedSharedPreferences`:**
     Thư viện `androidx.security:security-crypto` (`EncryptedSharedPreferences`, `MasterKey`) đã chính thức bị Google deprecate toàn bộ API (từ phiên bản `1.1.0-alpha07`, 04/2025) và Google đã dừng phát hành các bản cập nhật mới. Việc sử dụng một thư viện đã bị khai tử trong dự án mới là rủi ro kỹ thuật lớn về tính ổn định và bảo mật lâu dài.
   - **Kiến trúc `SecureTokenStore` đề xuất:**
     Xây dựng interface trừu tượng để tách biệt hoàn toàn tầng lưu trữ bí mật khỏi logic nghiệp vụ và hỗ trợ test:
     ```kotlin
     /** Lưu trữ credential bí mật đã được mã hóa. Tuyệt đối không log giá trị. */
     interface SecureTokenStore {
         suspend fun put(key: String, secret: String)
         suspend fun get(key: String): String?
         suspend fun remove(key: String)
         suspend fun removeByPrefix(prefix: String)
     }
     ```
   - **Cơ chế triển khai (Implementation):**
     - Sử dụng **Jetpack DataStore Preferences** lưu chuỗi mã hóa `Base64(iv + ciphertext)`.
     - Mã hóa bằng thuật toán `AES/GCM/NoPadding` với khóa AES-256 sinh và bảo vệ an toàn trong phần cứng `AndroidKeyStore` (`KeyGenParameterSpec` với `PURPOSE_ENCRYPT or PURPOSE_DECRYPT`, `BLOCK_MODE_GCM`, `ENCRYPTION_PADDING_NONE`), đóng gói trong lớp hỗ trợ `TokenCipher` (hoặc thư viện Google Tink kết hợp DataStore).
     - **Xử lý ngoại lệ an toàn:** Khi gặp lỗi giải mã (như `AEADBadTagException`, hoặc `KeyPermanentlyInvalidatedException` khi người dùng thay đổi bảo mật màn hình khóa): tự động xóa bản ghi lỗi và chuyển trạng thái kết nối tương ứng sang `ConnectionStatus.TOKEN_EXPIRED` (hoặc `DISCONNECTED`), tuyệt đối không làm crash app.
     - **Loại trừ khỏi Auto Backup (`android:dataExtractionRules` / `fullBackupContent`):** File DataStore này bắt buộc phải được loại trừ khỏi tính năng sao lưu tự động của hệ điều hành. Do khóa Keystore gắn chặt với phần cứng thiết bị vật lý và không bao giờ được backup lên cloud, dữ liệu khôi phục sang thiết bị khác sẽ không thể giải mã được.
     - **Khả năng kiểm thử (Testability):** Cho phép unit test chạy xác định (deterministic) mà không cần Keystore thật bằng cách inject `FakeSecureTokenStore` (sử dụng in-memory `ConcurrentHashMap`). Thư viện `androidx.datastore:datastore-preferences` được khai báo trong `gradle/libs.versions.toml` kèm lý do theo Quy tắc 7 của `AGENTS.md`. Vị trí interface (`core/` hay `data/`) do nhóm chốt qua issue `contract`.
   - **Phân định khóa lưu trữ theo mô hình Credential & Account (B8):**
     - `cred/{credentialId}`: Lưu User Token (Facebook), Member Token (LinkedIn), hoặc `bff_session_token`.
     - `fb_page/{pageId}`: Lưu Page Access Token riêng của từng Facebook Page.
   - **Room Database tuyệt đối KHÔNG lưu plaintext token:** Room chỉ lưu trữ các metadata phi nhạy cảm của tài khoản và kênh/Page: `accountId`, `credentialId`, `channelType`, `accountName`, `avatarUrl`, `expiresAt`, `connectionStatus`, `capabilities`. Khi gọi API, Repository sẽ lấy metadata từ Room và truy xuất token tương ứng từ `SecureTokenStore`.
   - **Quy chuẩn gửi Token & Logging (Tuân thủ Quy tắc 5 — B2):**
     - **Tuyệt đối không truyền token trong query string URL hoặc request body:** Mọi request Graph API và LinkedIn REST API đều gửi token qua header chuẩn:
       ```http
       Authorization: Bearer {access-token}
       ```
     - **Cấu hình HttpLoggingInterceptor an toàn:** Interceptor ghi log chỉ được kích hoạt trên bản build Debug (`BuildConfig.DEBUG == true`) và bắt buộc phải gọi `.redactHeader("Authorization")` để che giấu token, ngăn chặn hoàn toàn việc rò rỉ token nhạy cảm ra Logcat hoặc báo cáo crash.
   - **Quy trình bảo mật khi "Ngắt kết nối" (Disconnect Flow — F1 & B9):**
     Khi người dùng bấm ngắt kết nối một tài khoản hoặc kênh trên ứng dụng:
     - **Đối với Kênh Facebook:**
       - *Thu hồi quyền phía Meta (Best-effort):* Trước khi dọn dẹp ở client, app gọi request `DELETE https://graph.facebook.com/v26.0/me/permissions` kèm User Access Token để hủy bỏ quyền truy cập đã cấp cho ứng dụng trên tài khoản Facebook của người dùng.
       - *Xóa phiên SDK / BFF:* Nếu dùng Meta SDK, gọi `LoginManager.getInstance().logOut()`. Nếu dùng BFF, gửi `POST /auth/facebook/logout` kèm `bff_session_token` để hủy session cache.
       - *Dọn dẹp phía Client:* Xóa sạch User Token và toàn bộ Page Access Token liên quan khỏi `SecureTokenStore`; cập nhật hoặc xóa metadata kênh trong Room DB.
     - **Đối với Kênh LinkedIn:**
       - *Hạn chế thu hồi phía Native PKCE:* Do giao thức Native PKCE không hỗ trợ endpoint thu hồi token công khai mà không có Client Secret, app không thể tự động thu hồi token trên máy chủ LinkedIn từ client. Ứng dụng hướng dẫn người dùng: *"Để thu hồi hoàn toàn quyền truy cập, vui lòng truy cập Cài đặt & Quyền riêng tư $\rightarrow$ Ứng dụng bên thứ ba trên LinkedIn."*
       - *Nếu dùng luồng qua BFF:* Gửi request `POST /auth/linkedin/logout` kèm `bff_session_token` và `account_id` để máy chủ xóa `refresh_token` trong DB.
       - *Dọn dẹp phía Client:* Xóa sạch `access_token`, `refresh_token` (nếu có), và `bff_session_token` khỏi `SecureTokenStore`; cập nhật trạng thái trong Room DB thành `ConnectionStatus.DISCONNECTED`.

### 5.4. Kiến trúc tích hợp và bài toán đa tài khoản

#### Vấn đề cần giải quyết

`SocialChannelClient` hiện định danh implementation bằng `ChannelType`, trong khi token thực tế
được cấp cho từng tài khoản hoặc Page. Vì vậy, chỉ inject một client cố định theo `FACEBOOK` hoặc
`LINKEDIN` là chưa đủ để xử lý các trường hợp sau:

- Người dùng kết nối nhiều Facebook Page dưới cùng một tài khoản Facebook.
- Người dùng kết nối đồng thời LinkedIn Member và LinkedIn Organization.
- Một bài viết, comment hoặc số liệu phải được gọi đúng bằng token của `accountId` tương ứng.

Nếu chọn token chỉ theo `ChannelType`, request có thể dùng nhầm Page Access Token. Hậu quả là bài
viết được đăng lên sai Page, comment không đọc được hoặc số liệu bị gán nhầm tài khoản. `accountId`
phải được coi là một phần của ngữ cảnh gọi API, không phải dữ liệu chỉ dùng để hiển thị trên UI.

#### Phương án được đề xuất & Mô hình Tách biệt Credential và Account (B8)

Implementation thật của các client dự kiến đặt trong `data/social/` (hoặc tách thành
`data/facebook/` và `data/linkedin/`), gồm `FacebookClient.kt` và `LinkedInClient.kt`.
Các feature chỉ phụ thuộc `core/social/SocialChannelClient.kt`; Hilt cung cấp implementation
qua module tương ứng trong `di/`, không import trực tiếp lớp trong `data/` của mảng khác.

##### 1. Mô hình phân tách `credentialId` và `accountId` trong Room & Secure Storage (B8):
Token thực tế được cấp cho **thực thể xác thực** (Facebook User hoặc LinkedIn Member), không cấp riêng cho Organization. Một người dùng quản trị 3 Organization Pages chỉ có **1 token duy nhất** cho App Org. Nếu lưu token theo `accountId` của từng Organization, token sẽ bị nhân bản 3 lần và dẫn đến rủi ro lệch đồng bộ khi cập nhật hoặc ngắt kết nối. Do đó, Room DB tách thành 2 bảng quan hệ:

| Bảng Room | Các trường dữ liệu chính | Vai trò & Quy tắc |
|---|---|---|
| `credential` | `credentialId` (ví dụ `fb_user_{userId}`, `li_org_{personId}`, `li_member_{personId}`), `channelType`, `expiresAt`, `connectionStatus` | Đại diện cho phiên đăng nhập của người dùng. Lưu thời hạn token và trạng thái kết nối chung. |
| `channel_account` | `accountId` (Page ID / `urn:li:organization:{id}` / `urn:li:person:{id}`), `credentialId`, `channelType`, `accountName`, `avatarUrl`, `capabilities`, `tasks` (với FB) | Đại diện cho kênh/trang cụ thể mà agent tương tác. Tham chiếu khóa ngoại tới `credentialId`. |

**Quy tắc tra cứu và quản lý token trong `SecureTokenStore`:**
- `cred/{credentialId}`: Lưu User Access Token (Facebook) hoặc Member Access Token (LinkedIn).
- `fb_page/{pageId}`: Lưu Page Access Token riêng của từng Facebook Page.
- **Ngắt kết nối một Organization:** Chỉ xóa bản ghi trong `channel_account`. Chỉ khi credential không còn bất kỳ account nào tham chiếu thì mới dọn dẹp token trong `SecureTokenStore` và xóa bản ghi `credential`.
- **Cập nhật token:** Khi đăng nhập lại hoặc làm mới token, hệ thống chỉ cập nhật **một lần** vào `cred/{credentialId}`, toàn bộ các `channel_account` liên kết tự động sử dụng token mới.

##### 2. Thiết kế Factory & Giải quyết bài toán Root Post URN khi Reply (B12):
Mở một issue nhãn `contract` để thiết kế factory và hàm bổ sung theo hướng:

```kotlin
interface SocialChannelClientFactory {
    fun create(accountId: String): SocialChannelClient
}
```

Đồng thời, đối với bài toán reply comment trên LinkedIn (LinkedIn API yêu cầu URN bài viết gốc `rootPostUrn`):
- **Tuyệt đối không để `LinkedInClient` tự query Room DB:** Client ở tầng mạng (`data/social/`) không được phụ thuộc ngược vào Room Database, nhằm tuân thủ nghiêm ngặt kiến trúc MVVM (`Repository -> DataSource/Client`) và bảo đảm khả năng unit test độc lập.
- **Repository chịu trách nhiệm điều phối:** Repository tra cứu `Comment.remotePostId` từ Room DB và truyền xuống client.
- **Đề xuất hàm bổ sung trong `SocialChannelClient` (không sửa chữ ký cũ, tuân thủ AGENTS.md Quy tắc 1):**
  ```kotlin
  // Đề xuất thêm hàm overload có giá trị mặc định qua issue contract
  suspend fun reply(comment: Comment, text: String): Result<Comment> =
      reply(comment.id, text)
  ```

Trước khi mở rộng interface, nhóm cần thống nhất:
1. `accountId` là ID ổn định của Page/Organization/Member và phải khác với `ChannelType`.
2. Factory phải kiểm tra metadata trong Room để xác định `ChannelType`, trạng thái kết nối và
   capability trước khi tạo client.
3. Client chỉ lấy token sau khi đã có `accountId` và `credentialId`; token được tra cứu từ
   `SecureTokenStore` bằng key chuẩn hóa namespace (`cred/{credentialId}` hoặc `fb_page/{pageId}`). Không lưu token plaintext trong Room.
4. `publish`, `fetchComments`, `reply` và `fetchMetrics` phải dùng cùng account context trong
   toàn bộ một thao tác. Không suy ra account từ `remotePostId` hoặc từ client mặc định.
5. Khi `accountId` không tồn tại, đã ngắt kết nối, hết hạn token hoặc thiếu capability, factory/
   repository phải trả lỗi rõ ràng; không được fallback im lặng sang tài khoản khác.
6. Với LinkedIn, `Comment.remotePostId` được Repository trích xuất để cung cấp URN bài viết gốc khi reply, còn
   `accountId` quyết định Organization/Member token dùng cho request.

#### Trình tự gọi dự kiến

```text
UI/ViewModel
    -> Repository(accountId)
    -> [Repository đọc metadata từ Room (lấy credentialId, account info, remotePostId nếu reply)]
    -> SocialChannelClientFactory.create(accountId)
    -> Client lấy token từ SecureTokenStore theo credentialId / accountId
    -> API của Facebook Page hoặc LinkedIn Member/Organization
```

#### Phạm vi và tiêu chí chấp nhận

- Không thay đổi chữ ký hiện tại của `SocialChannelClient` trong issue nghiên cứu này.
- Issue contract phải bao gồm migration cho Hilt, Repository, fake client và test đa tài khoản.
- Test bắt buộc chứng minh hai `accountId` dùng hai token và endpoint context khác nhau; không chỉ
  kiểm tra khác `ChannelType`.
- **Test kịch bản đa tài khoản dùng chung credential (B8):** Kiểm thử 2 Organization dùng chung 1 `credentialId`; ngắt kết nối một Organization thì Organization còn lại vẫn hoạt động bình thường; khi credential hết hạn thì cả hai Organization đều chuyển sang trạng thái hết hạn token.
- Phải có test khi một account bị disconnect/expired để bảo đảm request bị từ chối rõ ràng và không
  rơi sang account khác.
- **Yêu cầu Unit Test bắt buộc cho cơ chế chống trùng lặp (Idempotency & Race scenarios):**
  1. *API thành công nhưng ghi Room thất bại:* Giả lập API trả về `remotePostId` thành công nhưng lệnh ghi Room DB ném lỗi/crash; lần retry kế tiếp phát hiện bài qua key hoặc đối soát feed và khôi phục ID mà không gọi API tạo bài mới.
  2. *Timeout không rõ kết quả $\rightarrow$ Đối soát tìm thấy bài:* Giả lập socket timeout sau khi đã gửi body request lên server; hàm đối soát feed (`GET /{page-id}/feed` hoặc `GET /rest/posts`) tìm thấy bài viết vừa tạo $\rightarrow$ tự động khôi phục `remotePostId` và chuyển trạng thái `PUBLISHED` mà không đăng lại.
  3. *Timeout không rõ kết quả $\rightarrow$ Đối soát không thấy bài:* Giả lập socket timeout và đối soát feed xác nhận bài chưa hề được tạo trên server $\rightarrow$ thực hiện đăng lại an toàn đúng 1 lần.
  4. *Xử lý tương tranh (Race condition):* Hai Worker hoặc Coroutine chạy song song với cùng một `idempotencyKey` $\rightarrow$ cơ chế khóa/trạng thái `PUBLISHING` trong Room bảo đảm chỉ duy nhất một lệnh gọi mạng được thực thi, worker thứ hai chờ hoặc bỏ qua, không gây đăng lặp.

---

## 6. Checklist trước khi triển khai

Các mục dưới đây phải được xác nhận bằng tài liệu portal hoặc integration test trước khi chuyển từ
`FakeSocialChannelClient` sang client thật:

- [ ] Meta Developer Portal xác nhận loại Login đang bật, `config_id` (nếu dùng Login for Business),
      redirect URI và các quyền cần App Review.
- [ ] Facebook Page test xác nhận các thao tác đăng bài, đọc/trả lời comment và lấy metrics bằng token
      Page thật; không suy luận capability chỉ từ HTTP status.
- [ ] Xác nhận metric insights và field follower đang khả dụng trên version Graph API được App cấp.
- [ ] LinkedIn xác nhận sản phẩm Community Management, quyền Organization và định dạng URN trả về từ
      endpoint đăng bài.
- [ ] Nhóm duyệt BFF LinkedIn (`backend/linkedin-bff/`), ghi nhận ngoại lệ Quy tắc 5 (issue `contract`), deploy BFF và đặt `LINKEDIN_MEMBER_CLIENT_SECRET` trên máy chủ; thêm Redirect URL của BFF ở LinkedIn Developer Portal.
      khai Standard OAuth.
- [ ] Kiểm thử retry, rate limit, token revoked/expired và luồng `HANDED_OFF` 2 pha (Worker bắn notification $\rightarrow$ Fragment foreground sao chép clipboard và mở Share Sheet) trên Android 10, 13, 14+.
- [ ] Kiểm thử cơ chế đối soát chống trùng lặp (reconciliation) trên feed Facebook (`GET /{page-id}/feed`) và LinkedIn (`GET /rest/posts?author=...`) trong trường hợp socket timeout để đảm bảo không đăng lặp bài viết lên mạng xã hội.
- [ ] Kiểm tra disconnect xóa token cục bộ; nếu có luồng BFF ngoài phạm vi thì phải có quy trình thu hồi server-side trước khi
      coi kết nối đã ngắt hoàn toàn.

## 7. Tóm tắt nội dung dùng cho Slide Báo cáo (Research Presentation)

Dưới đây là dàn ý nội dung được thiết kế sẵn theo từng slide (kèm gợi ý hình ảnh/sơ đồ và lời thuyết trình) giúp nhóm dễ dàng đưa vào PowerPoint / Google Slides:

### 🎯 Slide 1: So sánh khả năng kênh & Đối chiếu Kiến trúc (Channel Capabilities)
* **Ý chính trình chiếu:**
  * Đối chiếu trực tiếp với `enum class ChannelCapability`: `PUBLISH`, `READ_COMMENTS`, `REPLY`, `MENTION`, `METRICS`.
  * **Facebook Page:** Hỗ trợ đăng bài, comment/reply và metrics theo Page task, permission và version; mention là capability có điều kiện. Không hỗ trợ native Idempotency $\rightarrow$ Quản lý qua Room DB cục bộ.
  * **LinkedIn Organization:** Hỗ trợ Company Page theo product/quyền được cấp; mention là capability có điều kiện và cần xác minh; không hỗ trợ native Idempotency Key $\rightarrow$ Quản lý hàng đợi và chống trùng lặp bằng Room DB cục bộ.
  * **LinkedIn Member (Cá nhân):** Có thể đăng bài cơ bản khi app được cấp `w_member_social`; quyền đọc/trả lời comment, metrics và mention không được coi là capability mặc định.
* **Gợi ý Visual:** Bảng ma trận so sánh 3 cột (Facebook Page | LinkedIn Org | LinkedIn Member) với các icon tick xanh ✅ và cảnh báo ⚠️.

---

### 🔐 Slide 2: Kiến trúc Xác thực An toàn — Public Client Không Giữ Secret
* **Ý chính trình chiếu:**
  * **Chuẩn bảo mật Native (RFC 8252 & RFC 6750):** Ứng dụng Android là Public Client $\rightarrow$ Loại bỏ hoàn toàn `client_secret` khỏi APK và mã nguồn. Tuyệt đối không truyền `access_token` trong URL/Deep Link.
  * **Cơ chế bảo vệ mã ủy quyền trong Milestone 1:** Facebook dùng Meta Android SDK; LinkedIn dùng
    Native PKCE qua Loopback Redirect (`127.0.0.1`) nếu Developer Portal đã bật protocol, kèm
    xác thực `state` chống CSRF. AppAuth/Custom Tabs + BFF chỉ là thiết kế tham khảo ngoài phạm vi.
  * **Định danh ứng dụng an toàn:**
    * *Facebook:* Meta Android SDK (App ID + Package Name + SHA-1 Key Hash). Web OAuth qua BFF
      chỉ nằm trong tài liệu tham khảo ngoài phạm vi.
    * *LinkedIn:* Client ID công khai + luồng Native PKCE (`/oauth/native-pkce/`) qua Loopback Redirect
      không giữ secret. Standard OAuth qua BFF chỉ là phương án backend tham khảo.
  * **Vòng đời & Lưu trữ Token an toàn:** 
    * *Facebook:* Long-lived User Token thường khoảng 60 ngày nhưng không phải TTL cố định; Meta SDK
    quản lý một phần vòng đời token. Ứng dụng vẫn phải kiểm tra hết hạn/thu hồi và Page
    Token phải được kiểm thử trên Page development.
    * *LinkedIn:* Access Token thường có thời hạn 60 ngày $\rightarrow$ khi hết hạn có thể phải mở lại luồng OAuth và xác nhận consent hoặc đăng nhập lại; runtime phải dùng `expires_in` thực tế. Nếu Native
    PKCE chưa được cấp, tính năng bị blocked trong Milestone 1.
    * *Lưu trữ an toàn (`SecureTokenStore`):* Thay thế hoàn toàn `EncryptedSharedPreferences` (đã bị Google deprecate toàn bộ API trong `security-crypto`) bằng kiến trúc `SecureTokenStore` (DataStore Preferences + mã hóa AES-GCM bằng Android Keystore); Room DB chỉ quản lý metadata phi nhạy cảm. Luồng logout BFF và `bff_session_token` chỉ thuộc thiết kế backend tham khảo.
* **Gợi ý Visual:** Sơ đồ Meta SDK và LinkedIn Native PKCE Loopback cho Milestone 1; phần BFF chỉ
  được trình bày như phương án tương lai ngoài phạm vi.

---

### ⚙️ Slide 3: Endpoints Cốt lõi & Giới hạn Kỹ thuật (Technical Specs)
* **Ý chính trình chiếu:**
  * **Đăng bài có ảnh (F5):**
    * *Facebook:* 1 bước qua `POST /{page-id}/photos` (ảnh đơn) hoặc 2 bước qua `attached_media` (đa ảnh).
    * *LinkedIn:* `initializeUpload` $\rightarrow$ PUT binary $\rightarrow$ Đợi ảnh `AVAILABLE` $\rightarrow$ Đăng bài với Image URN (ảnh đơn) hoặc `multiImage` (2–20 ảnh) (⚠️ **Cần kiểm thử thực tế**) nếu product/quyền của app hỗ trợ; mỗi ảnh phải dưới 36.152.320 pixel (ảnh GIF tối đa 250 frames, altText tối đa 4.086 ký tự [LinkedIn MultiImage API](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/shares/multiimage-post-api)).
  * **Tương tác & Số liệu (F6, F7):** Đọc comment phân cấp, reply theo ID/URN, lấy insights (sử dụng metric được version hiện hành hỗ trợ, ví dụ `post_media_view`) và follower count.
  * **Giới hạn kiểm soát:**
    * *Độ dài bài:* Facebook (63.206 ký tự là cấu hình quy ước nội bộ trong `ChannelType` của app do tài liệu [Meta Pages API - Posts](https://developers.facebook.com/documentation/pages-api/posts) không công bố giới hạn ký tự cụ thể; client tự xử lý lỗi validation nếu vượt quá giới hạn) vs LinkedIn (3.000 ký tự cấu hình trong `ChannelType`, API trả về lỗi `FIELD_LENGTH_TOO_LONG` nếu vượt quá giới hạn tối đa theo [LinkedIn Posts API](https://learn.microsoft.com/en-us/linkedin/marketing/integrations/community-management/shares/posts-api) nhưng không nêu con số cụ thể trong tài liệu chính thức).
    * *Kích thước ảnh:* Facebook (tối đa dưới 4 MB/ảnh, riêng PNG khuyến nghị dưới 1 MB để tránh vỡ hạt [Meta Graph API - Page Photos](https://developers.facebook.com/docs/graph-api/reference/page/photos/); định dạng JPEG, BMP, PNG, GIF, TIFF) vs LinkedIn (dưới 36.152.320 pixel/ảnh [LinkedIn MultiImage API](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/shares/multiimage-post-api)).
    * *Rate limit:* Facebook (giới hạn động theo BUC trên Page token [Meta Graph API - Rate Limiting](https://developers.facebook.com/docs/graph-api/overview/rate-limiting/), ngưỡng động cụ thể cần quan sát theo response thực tế) vs LinkedIn (Community Management Development Tier tối đa 500 calls/app/24h và 100 calls/member/24h [LinkedIn Increasing Access](https://learn.microsoft.com/en-us/linkedin/marketing/increasing-access?view=li-lms-2026-10); các endpoint/product khác thay đổi theo ứng dụng [LinkedIn API Rate Limiting](https://learn.microsoft.com/en-us/linkedin/shared/api-guide/concepts/rate-limits)); quota thực tế phải kiểm tra trên Portal Analytics và response thực tế.
* **Gợi ý Visual:** Bảng so sánh 2 cột trực quan các chỉ số dung lượng, ký tự và Rate Limit.

---

### 🛡️ Slide 4: Rào cản Chính sách & Giải pháp "Đã bàn giao" (`HANDED_OFF`)
* **Ý chính trình chiếu:**
  * **Rào cản API bất khả kháng:**
    1. Pages API của Meta chỉ hỗ trợ đăng bài lên Facebook Page, không hỗ trợ đăng bài lên trang cá nhân người dùng và không còn quyền `publish_actions` trong danh mục quyền hiện hành [Meta Pages API - Overview](https://developers.facebook.com/documentation/pages-api/overview), [Meta Permissions Reference](https://developers.facebook.com/docs/permissions).
    2. Meta chỉ cho Page mention người đã đăng bài/comment khi có Page-scoped ID và đủ điều kiện; LinkedIn chỉ bật mention Member/Organization sau khi kiểm thử endpoint/product cụ thể với URN hợp lệ.
    3. Rào cản kiểm duyệt App Review và điều kiện Advanced Access thay đổi theo permission/product; Business Verification hoặc tài liệu doanh nghiệp chỉ được yêu cầu khi Meta hiển thị cho trường hợp cụ thể.
  * **Giải pháp 1 (Nội bộ):** Sử dụng cấp độ **Standard Access** (dành cho Business App không có App Mode toggle [Meta Pages API - Overview](https://developers.facebook.com/documentation/pages-api/overview)) $\rightarrow$ Thử nghiệm với các tài khoản nội bộ được gán vai trò Tester/Developer trong App Roles (hoặc thành viên trong Business Manager sở hữu App) và có Page Tasks quản trị trên Facebook Page thử nghiệm.
  * **Giải pháp 2 (Dự phòng F5 - Quy trình `HANDED_OFF` 2 pha):** 
    * **Pha nền (Worker):** Khi xác nhận lỗi phân quyền không thể tự khắc phục ở runtime $\rightarrow$ Worker cập nhật trạng thái `PostStatus.HANDED_OFF` vào Room DB và phát **System Notification** cho người dùng ("Bài viết cần đăng thủ công"); tuyệt đối không mở Activity hay ghi clipboard từ nền để tuân thủ Android 10+ background restriction.
    * **Pha tiền cảnh (Fragment):** Người dùng chạm notification mở chi tiết bài viết $\rightarrow$ Bấm nút "Đăng thủ công" $\rightarrow$ Sao chép text vào Clipboard + Khởi tạo Android Share Sheet (`Intent.ACTION_SEND` kèm file ảnh qua `FileProvider`). Hướng dẫn người dùng dán text vào app mạng xã hội (do Facebook bỏ qua `EXTRA_TEXT`).
* **Gợi ý Visual:** Sơ đồ luồng: `[Worker hàng đợi]` $\rightarrow$ `(Lỗi quyền)` $\rightarrow$ `[Lưu Room HANDED_OFF + Bắn Notification]` $\rightarrow$ `[Người dùng mở Fragment chi tiết]` $\rightarrow$ `[Bấm 'Đăng thủ công']` $\rightarrow$ `[Copy Clipboard + Mở Share Sheet native]`.

---

### 🚀 Slide 5: Kiến nghị Contract & Lộ trình Milestone 1
* **Ý chính trình chiếu:**
  * **Kiến nghị Contract (`SocialChannelClient`):** Như phân tích tại §5.4, đề xuất mở Issue nhãn
    `contract` để bổ sung `SocialChannelClientFactory`, giúp mỗi lệnh gọi gắn với đúng `accountId`
    (Facebook Page, LinkedIn Member hoặc LinkedIn Organization). Đây là đề xuất, chưa thay đổi
    `SocialChannelClient` hiện tại.
  * **Kế hoạch triển khai:**
    * *Tuần 1:* Họp nhóm chốt contract/context đa tài khoản, tiêu chí test tại §5.4 và hoàn thiện cấu
      hình Developer Portals; tạo App Org riêng cho Community Management API và xác nhận LinkedIn Native PKCE được bật cho cả 2 App ID (App Member và App Org).
    * *Tuần 2:* Triển khai Meta Android SDK cho Facebook và LinkedIn Native PKCE Loopback (`127.0.0.1`);
      tích hợp secure storage lưu token theo `accountId` và Room DB quản lý metadata; triển khai bộ unit test xác định cho hàng đợi đăng bài chống trùng lặp (idempotency, phân loại lỗi mạng và đối soát feed theo 4 ca kiểm thử tại §5.4). Không triển khai AppAuth/BFF trong Milestone 1.
  * **Checklist kiểm thử tích hợp thực tế (Integration Verification Checklist):**
    Các điểm cần gọi thử nghiệm thực tế với Access Token thật trên môi trường dev trong Tuần 1 để chốt cấu hình:
    1. **Quyền & Endpoint danh sách Organization (LinkedIn):** Xác nhận scope `r_organization_admin` là đủ cho thao tác đọc danh sách Organization Pages qua endpoint `GET /rest/organizationAcls?q=roleAssignee` (không cần xin thừa `rw_organization_admin`) [LinkedIn Organization Access Control by Role](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/organizations/organization-access-control-by-role); xác nhận trường `roleAssignee` mang URN cá nhân của quản trị viên (do App Org độc lập không có OIDC); đồng thời kiểm tra khả năng tích hợp endpoint `GET /v2/organizationAuthorizations` nếu cần kiểm tra quyền hạn chi tiết theo hành vi (ví dụ quyền đăng bài `ORGANIC_SHARE_CREATE`) thay vì chỉ dựa vào vai trò quản trị chung [LinkedIn Getting Started with Organization Authorizations](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/organizations/organization-authorizations/getting-started).
    2. **Field `followers_count` của Page node trên Graph API (Facebook):** (⚠️ Chưa xác minh (cần thử trên Portal/API thực tế)) Gọi thử `GET /{page-id}?fields=followers_count,fan_count` để xác nhận dữ liệu trả về thực tế trên New Page Experience [Meta Graph API - Page Reference](https://developers.facebook.com/docs/graph-api/reference/page).
    3. **Enum `CompanyFollowedByMember` của `networkSizes` (LinkedIn):** (⚠️ Chưa xác minh (cần thử trên Portal/API thực tế) [TEST-04]) Kiểm tra endpoint `GET https://api.linkedin.com/v2/networkSizes/urn:li:organization:{org-id}?edgeType=CompanyFollowedByMember` để đảm bảo gateway chấp nhận cú pháp theo tài liệu [LinkedIn Organization Network Size](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/organizations/organization-network-size); không tự ý dùng `/rest/` hay enum viết hoa khi chưa có tài liệu xác nhận [LinkedIn Organization Network Size](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/organizations/organization-network-size).
    4. **Hiệu lực của scope `pages_read_user_engagement` (Facebook):** (⚠️ Chưa xác minh (cần thử trên Portal/API thực tế) [TEST-01]) Kiểm tra trên App Dashboard và Graph API xem hệ thống chấp nhận scope `pages_read_user_engagement` (theo hướng dẫn [Meta Pages API - Posts](https://developers.facebook.com/documentation/pages-api/posts)) hay `pages_read_user_content` (theo từ điển quyền [Meta Permissions Reference](https://developers.facebook.com/docs/permissions)); đề xuất cấu hình ưu tiên xin `pages_read_user_content` kết hợp `pages_read_engagement` và `pages_show_list` [Meta Permissions Reference](https://developers.facebook.com/docs/permissions).
    5. **Thời hạn Page Access Token (Facebook):** (⚠️ Chưa xác minh (cần thử trên Portal/API thực tế) [TEST-02]) Kiểm tra `expires_at` thực tế của Page token sinh từ User Long-Lived Token qua Access Token Debugger để xác minh xem token có giá trị `0` (không có ngày hết hạn theo [Meta Long-Lived Page Access Tokens](https://developers.facebook.com/docs/facebook-login/guides/access-tokens/get-long-lived)) hay có hạn dùng cụ thể (theo [Meta Pages API - Overview](https://developers.facebook.com/documentation/pages-api/overview)); đồng thời triển khai bắt mã lỗi HTTP 400/401 `code: 190` khi token bị thu hồi động [Meta Graph API - Error Handling](https://developers.facebook.com/docs/graph-api/guides/error-handling).
    6. **Endpoint reply comment (Facebook):** (⚠️ Chưa xác minh (cần thử trên Portal/API thực tế) [TEST-03]) Gọi thử nghiệm để xác nhận Graph API chấp nhận endpoint Node/Edge chuẩn `POST /{comment-id}/comments` [Meta Graph API - Object Comments](https://developers.facebook.com/docs/graph-api/reference/object/comments) hay endpoint trực tiếp `POST /{comment-id}` [Meta Pages API - Comments & Mentions](https://developers.facebook.com/docs/pages-api/comments-mentions/); đề xuất app ưu tiên gọi `POST /{comment-id}/comments` và chuẩn bị fallback nếu nhận lỗi 400/404 [Meta Graph API - Object Comments](https://developers.facebook.com/docs/graph-api/reference/object/comments).
    7. **Scope Share Statistics (LinkedIn):** (⚠️ Chưa xác minh (cần thử trên Portal/API thực tế) [TEST-05]) Gọi thử `GET /rest/organizationalEntityShareStatistics` với token chỉ có `rw_organization_admin` [LinkedIn Organization Share Statistics](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/organizations/share-statistics) để xác minh xem có bị lỗi HTTP 403 khi thiếu `r_organization_social` không; đề xuất cấu hình app xin gộp cả `rw_organization_admin` và `r_organization_social` [LinkedIn Organization Share Statistics](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/organizations/share-statistics).
    8. **Scope OpenID Connect trên Native PKCE của App Member (LinkedIn):** (❌ **Đã thử thực tế [TEST-06]: LinkedIn từ chối** — thông báo "Open ID permission is not supported for PKCE flows". Kết luận: App Member không dùng Native PKCE, chuyển sang Luồng B qua BFF) Thử nghiệm gửi `scope=openid profile` trên endpoint `/oauth/native-pkce/authorization` [LinkedIn OAuth 2.0 Native Clients (PKCE)](https://learn.microsoft.com/en-us/linkedin/shared/authentication/authorization-code-flow-native), [LinkedIn Sign In with LinkedIn using OpenID Connect](https://learn.microsoft.com/en-us/linkedin/consumer/integrations/self-serve/sign-in-with-linkedin-v2) cho riêng App Member để xác nhận máy chủ LinkedIn có chấp nhận OIDC cho native client không; nếu API báo lỗi không hỗ trợ thì đánh dấu blocked tính năng kết nối cá nhân để chờ LinkedIn duyệt, tuyệt đối không fallback về `scope=r_liteprofile` đã bị khai tử [LinkedIn OAuth 2.0 Native Clients (PKCE)](https://learn.microsoft.com/en-us/linkedin/shared/authentication/authorization-code-flow-native).
    9. **Cơ chế đối soát chống trùng lặp (Reconciliation):** (⚠️ Chưa xác minh (cần thử trên Portal/API thực tế) [TEST-07]) Thử nghiệm đối soát qua endpoint feed (`GET /{page-id}/feed?since=...` trên Facebook và `GET /rest/posts?author=...` trên LinkedIn) để đảm bảo client có thể tra cứu và khôi phục `remotePostId` của bài vừa tạo trong trường hợp request bị timeout sau khi gửi dữ liệu lên server.
* **Gợi ý Visual:** Timeline lộ trình thực hiện theo tuần của Milestone 1.


## 8. Danh mục nguồn tham chiếu chính thức (Official References)

Bảng tổng hợp toàn bộ các tài liệu chính thức từ Meta for Developers, Microsoft Learn (LinkedIn REST API) và quy định nội bộ dự án được dẫn nguồn trực tiếp trong báo cáo nghiên cứu:

### 8.1. Meta for Developers (Facebook Graph API & Facebook Login)

| STT | Tài liệu tham chiếu | URL chính thức | Nội dung trích dẫn & Mục áp dụng trong báo cáo |
|:---:|---|---|---|
| [1] | **Meta Pages API - Overview** | [Link](https://developers.facebook.com/documentation/pages-api/overview) | Tổng quan Pages API, luồng gọi `/me/accounts`, vai trò Page Tasks, hạn dùng Page token (§2.1, §2.3) |
| [2] | **Meta Pages API - Posts** | [Link](https://developers.facebook.com/documentation/pages-api/posts) | Đăng bài feed (`POST /{page-id}/feed`), đăng link, scope `pages_manage_posts`, Page Tasks (`CREATE_CONTENT`, `MANAGE`, `MODERATE`) (§2.3) |
| [3] | **Meta Pages API - Comments & Mentions** | [Link](https://developers.facebook.com/docs/pages-api/comments-mentions/) | Cấu trúc phân trang comments, ví dụ endpoint reply `POST /{comment-id}`, điều kiện mention (§1, §2.3) |
| [4] | **Meta Graph API - Object Comments** | [Link](https://developers.facebook.com/docs/graph-api/reference/object/comments) | Đọc comment (`GET /{post-id}/comments`), phản hồi comment (`POST /{comment-id}/comments`), trường summary (§2.3) |
| [5] | **Meta Graph API - Page Photos** | [Link](https://developers.facebook.com/docs/graph-api/reference/page/photos/) | Đăng ảnh đơn (`POST /{page-id}/photos`), tham số `caption`, `published=false`, đăng đa ảnh qua `attached_media` trên feed, giới hạn dung lượng/định dạng ảnh (§2.3) |
| [6] | **Meta Graph API - Page Insights** | [Link](https://developers.facebook.com/docs/graph-api/reference/page/insights/) | Đo lường số liệu Page (`GET /{page-id}/insights`), quyền `read_insights`, Page task `ANALYZE` (§2.3) |
| [7] | **Meta Graph API - Page Reference** | [Link](https://developers.facebook.com/docs/graph-api/reference/page) | Đặc tả Page node, trường `fan_count`, `followers_count` trên New Page Experience (§2.3, §6) |
| [8] | **Meta Graph API - Error Handling** | [Link](https://developers.facebook.com/docs/graph-api/guides/error-handling) | Bảng mã lỗi Graph API (mã `190` token invalid, `200` permissions error, `4`/`17`/`341` rate limit) (§2.1, §2.4) |
| [9] | **Meta Graph API - Rate Limiting** | [Link](https://developers.facebook.com/docs/graph-api/overview/rate-limiting/) | Cơ chế giới hạn tốc độ BUC (Business Use Case) cho Page Access Token, phân biệt Platform Rate Limit (§2.4) |
| [10] | **Meta Long-Lived Page Access Tokens** | [Link](https://developers.facebook.com/docs/facebook-login/guides/access-tokens/get-long-lived) | Luồng đổi User Long-lived token sang Page Long-lived token không thời hạn (`expires_at: 0`) (§2.1) |
| [11] | **Meta Authentication Versus Data Access** | [Link](https://developers.facebook.com/documentation/facebook-login/auth-vs-data#permission-expiration) | Quy định hết hạn quyền sau 90 ngày không hoạt động (*Permission Expiration*) (§2.4) |
| [12] | **Meta Facebook Login for Business** | [Link](https://developers.facebook.com/docs/facebook-login/facebook-login-for-business/) | Cơ chế cấu hình Configuration ID (`config_id`) cho ứng dụng quản lý tài sản doanh nghiệp (§2.1, §5.1) |
| [13] | **Meta Permissions Reference** | [Link](https://developers.facebook.com/docs/permissions) | Từ điển danh mục quyền chính thức (`pages_manage_posts`, `pages_manage_engagement`, `pages_read_user_content`...) (§2.2) |
| [14] | **Meta Graph API Changelog** | [Link](https://developers.facebook.com/docs/graph-api/changelog) | Lịch phát hành và chu kỳ hỗ trợ các phiên bản Graph API (§2.0) |
| [15] | **Meta Graph API Changelog v25.0** | [Link](https://developers.facebook.com/docs/graph-api/changelog/version25.0) | Danh sách metric Insights bị deprecated và metric thay thế (`post_media_view`) (§2.3) |
| [16] | **Meta Deprecated Page Insights Metrics** | [Link](https://developers.facebook.com/documentation/pages-api/platforminsights/page/deprecated-metrics/) | Danh mục chi tiết các metric Insights ngừng hỗ trợ (`page_fans`, `post_impressions`...) (§2.3) |
| [17] | **Meta Manual Login Flow** | [Link](https://developers.facebook.com/documentation/facebook-login/guides/advanced/manual-flow) | Quy chuẩn Web OAuth dialog tham khảo cho kiến trúc BFF tương lai (§2.1) |
| [18] | **Meta Access Tokens Guide** | [Link](https://developers.facebook.com/documentation/facebook-login/guides/access-tokens) | Phân loại User Token, Page Token, Client Token và cơ chế kiểm tra token debug (§2.1) |
| [19] | **Meta Access Token Debugger** | [Link](https://developers.facebook.com/tools/debug/accesstoken/) | Công cụ kiểm tra trực quan thời hạn, scopes và metadata của token trong giai đoạn Dev (§2.1) |
| [20] | **Meta Graph API - Page Post Reference** | [Link](https://developers.facebook.com/docs/graph-api/reference/post/) | Đặc tả cấu trúc Page Post node, trường `shares` và các edge tương tác của bài viết (§1, §2.3) |

### 8.2. Microsoft Learn (LinkedIn REST API)

| STT | Tài liệu tham chiếu | URL chính thức | Nội dung trích dẫn & Mục áp dụng trong báo cáo |
|:---:|---|---|---|
| [21] | **LinkedIn OAuth 2.0 Native Clients (PKCE)** | [Link](https://learn.microsoft.com/en-us/linkedin/shared/authentication/authorization-code-flow-native) | Giao thức Native PKCE (`/oauth/native-pkce/`), loopback redirect, thu hồi token khi đổi scope (§3.1) |
| [22] | **LinkedIn 3-Legged OAuth Flow** | [Link](https://learn.microsoft.com/en-us/linkedin/shared/authentication/authorization-code-flow) | Luồng Authorization Code Grant chuẩn (`/oauth/v2/authorization`, `/oauth/v2/accessToken`) cho BFF (§3.1) |
| [23] | **LinkedIn Refresh Tokens with OAuth 2.0** | [Link](https://learn.microsoft.com/en-us/linkedin/shared/authentication/programmatic-refresh-tokens) | Vòng đời refresh token (365 ngày), cơ chế đổi refresh token lấy access token mới (§3.1) |
| [24] | **LinkedIn Increasing Access** | [Link](https://learn.microsoft.com/en-us/linkedin/marketing/increasing-access?view=li-lms-2026-10) | Hạn ngạch Community Management Development Tier (500 calls/app/24h, 100 calls/member/24h), duyệt quyền (§3.4) |
| [25] | **LinkedIn API Rate Limiting** | [Link](https://learn.microsoft.com/en-us/linkedin/shared/api-guide/concepts/rate-limits) | Cơ chế rate limit theo Product/Endpoint, cảnh báo email 75% quota, theo dõi qua tab Analytics (§3.4) |
| [26] | **LinkedIn Posts API** | [Link](https://learn.microsoft.com/en-us/linkedin/marketing/integrations/community-management/shares/posts-api) | Tạo bài viết (`POST /rest/posts`), quyền `w_organization_social`, vai trò Page (`CONTENT_ADMIN`...) (§3.2, §3.3) |
| [27] | **LinkedIn MultiImage API** | [Link](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/shares/multiimage-post-api) | Đăng bài đa ảnh, giới hạn < 36.152.320 pixel/ảnh, định dạng JPG/GIF/PNG, tham chiếu Images API (§3.3, §3.4) |
| [28] | **LinkedIn Little Text Format** | [Link](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/shares/little-text-format) | Cú pháp mention `@[Tên](urn:li:...)`, quy định escape 15 ký tự đặc biệt (`&#124;`, `{`, `}`, `@`...) (§3.3) |
| [29] | **LinkedIn Comments API** | [Link](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/shares/comments-api) | Đọc comment (`GET /rest/socialActions/{urn}/comments`), trả lời comment, rate limit 1 phút (§3.3) |
| [30] | **LinkedIn Network Update Social Actions** | [Link](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/shares/network-update-social-actions) | Tương tác like/comment, tham số phân trang truyền thống (`start`, `count`) (§3.3) |
| [31] | **LinkedIn Social Metadata API** | [Link](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/shares/social-metadata-api) | Đọc metadata phản hồi mạng xã hội, quy định quyền cá nhân `r_member_social_feed` (§1, §3.3) |
| [32] | **LinkedIn Organization Access Control by Role** | [Link](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/organizations/organization-access-control-by-role) | Phân quyền vai trò Page (`ADMINISTRATOR`, `CONTENT_ADMINISTRATOR`), endpoint legacy `/rest/organizationAcls` (§3.3) |
| [33] | **LinkedIn Getting Started with Organization Authorizations** | [Link](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/organizations/organization-authorizations/getting-started) | Kiểm tra quyền hạn chi tiết theo hành vi của thành viên trên Organization (`GET /v2/organizationAuthorizations`) (§3.3) |
| [34] | **LinkedIn Organization Share Statistics** | [Link](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/organizations/share-statistics) | Lấy chỉ số tương tác bài viết công ty (`GET /rest/organizationalEntityShareStatistics`), scope `rw_organization_admin` (§3.3) |
| [35] | **LinkedIn Organization Follower Statistics** | [Link](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/organizations/follower-statistics) | Thống kê số lượng người theo dõi công ty (`GET /rest/organizationalEntityFollowerStatistics`) (§3.3) |
| [36] | **LinkedIn Organization Network Size** | [Link](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/organizations/organization-network-size) | Lấy tổng số follower thời gian thực (`GET /v2/networkSizes/...edgeType=CompanyFollowedByMember`) (§3.3) |
| [37] | **LinkedIn Sign In with LinkedIn using OpenID Connect** | [Link](https://learn.microsoft.com/en-us/linkedin/consumer/integrations/self-serve/sign-in-with-linkedin-v2) | Luồng đăng nhập OpenID Connect chuẩn (`scope=openid profile`), phân biệt với scope `r_liteprofile` (§3.1, §3.2) |
| [38] | **LinkedIn Developer Portal** | [Link](https://developer.linkedin.com/) | Giao diện quản trị ứng dụng, cấu hình OAuth Redirect, kiểm tra hạn mức tại tab Analytics (§3.4, §5.2) |
| [39] | **LinkedIn Images API** | [Link](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/shares/images-api?view=li-lms-2026-10) | Khởi tạo upload ảnh (`action=initializeUpload`), upload binary lên `uploadUrl`, kiểm tra trạng thái ảnh `AVAILABLE` (§3.3) |
| [40] | **LinkedIn API Partner Support Guide** | [Link](https://learn.microsoft.com/en-us/linkedin/shared/linkedin-api-partner-support-guide) | Quy định Community Management API phải là sản phẩm duy nhất trên developer app (§0, §5.2) |
| [41] | **LinkedIn Community Management Overview** | [Link](https://learn.microsoft.com/en-us/linkedin/marketing/community-management/community-management-overview) | Tổng quan bộ API quản trị cộng đồng và ràng buộc sản phẩm độc lập (§0, §5.2) |

### 8.3. Tài liệu Quy chuẩn Nội bộ Dự án (Internal Guidelines)

| STT | Tài liệu tham chiếu | Vị trí quy định | Nội dung áp dụng trong kiến trúc |
|:---:|---|---|---|
| [42] | **AGENTS.md** | Quy tắc bắt buộc số 5 | *"Không bao giờ log, commit hay gửi API key/token lên bất kỳ server nào ngoài nhà cung cấp LLM/mạng xã hội. Key lưu bằng Android Keystore."* — Quy định bất biến cho Public Client Android (§0, §2.1, §3.1, §5.3) |
| [43] | **AGENTS.md** | Quy tắc bắt buộc số 1 & 3 | Không đổi chữ ký interface `core/`; feature gọi qua interface `SocialChannelClient`, không phụ thuộc implementation (`FacebookClient`, `LinkedInClient`) (§0, §5.4) |
| [44] | **AGENTS.md** | Quy tắc bắt buộc số 6 | Agent không được tự động đăng bài khi người dùng chưa duyệt (F4) — Ràng buộc trạng thái `PostStatus.DRAFT` / `SCHEDULED` (§0, §4.2) |

### 8.4. Tài liệu Kỹ thuật Bảo mật & Di chuyển Thư viện (Android Security & Migration References)

| STT | Tài liệu tham chiếu | URL chính thức | Nội dung áp dụng trong kiến trúc |
|:---:|---|---|---|
| [45] | **ProAndroidDev – Goodbye EncryptedSharedPreferences** | [Link](https://proandroiddev.com/goodbye-encryptedsharedpreferences-a-2026-migration-guide-4b819b4a537a) | Hướng dẫn di chuyển từ `EncryptedSharedPreferences` sang DataStore Preferences + Android Keystore AES-GCM (§5.3) |
| [46] | **Include Security – EncryptedSharedPreferences is Dead** | [Link](https://blog.includesecurity.com/2026/08/encryptedsharedpreferences-is-dead-heres-what-you-should-use-instead/) | Phân tích rủi ro kỹ thuật và việc Google ngừng duy trì thư viện `androidx.security:security-crypto` (§5.3) |
