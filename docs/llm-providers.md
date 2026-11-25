# Tích hợp LLM provider — Issue #52

`LlmClient` được Hilt cung cấp bởi `LlmModule`, implementation là `RoutingLlmClient`.
F2/F6 vẫn gọi interface trong `core/ai/`, không cần biết adapter đang dùng.

```text
Fragment → ViewModel → Repository / ContentAgent → LlmClient
  → cấu hình theo LlmTask → ApiKeySource → OpenAiClient / GeminiClient → HTTPS SSE
```

## Cấu hình và ranh giới với #53

- `LlmConfigurationSource.configurationFor(task)` cung cấp provider, model và khả năng nhận
  `temperature` của model. `InMemoryLlmConfiguration` giữ lựa chọn trong phiên, mặc định chưa cấu hình.
- `ApiKeySource.keyFor(provider)` là điểm #53 nối secure storage. Router đọc lại key ngay trước
  mỗi request, không giữ key trong cấu hình, disk, UiState hay cache của router.
- #53 nối `LlmModule.apiKeys(store)` với `ApiKeyStore.read(provider)` từ secure storage.
  Chỉ một binding `ApiKeySource`; mỗi request đọc lại bản mã và giải mã tạm thời, không đổi `core/`.
  Storage/verifier ở `data/apikeys/`; màn quản lý key ở `feature/apikeys/`, mở từ mục API key trong Cài đặt.
- Không có key/model mặc định. Thiếu cấu hình trả `MISSING_CONFIGURATION`; thiếu key trả
  `MISSING_API_KEY`, không ngầm dùng Fake hoặc provider khác.
- `WRITE_POST` phục vụ chat/soạn trả lời; `CLASSIFY_COMMENT` phục vụ phân loại. `GENERATE_IMAGE`
  bị từ chối với `UNSUPPORTED_TASK` vì adapter ảnh nằm ngoài #52.
- Khi model không hỗ trợ sampling temperature, đặt `supportsTemperature = false`.
  Model ID do bên cấu hình cung cấp; #52 không khẳng định quyền truy cập hoặc tính khả dụng của model.

## Giao thức và lifecycle

- OpenAI: `POST https://api.openai.com/v1/responses`, `stream=true`, `store=false`.
  Chỉ nối `response.output_text.delta`; không nối lại text ở event `done`.
  Chỉ hoàn thành khi nhận `response.completed` hợp lệ; refusal/incomplete/failed là lỗi.
- Gemini: `POST https://generativelanguage.googleapis.com/v1beta/models/{model}:streamGenerateContent?alt=sse`.
  System messages vào `systemInstruction`, assistant messages dùng role `model`.
  Chỉ lấy candidate 0, bỏ phần `thought`; cần `finishReason=STOP` để xác nhận hoàn tất.
- Key chỉ ở header `Authorization` / `x-goog-api-key`, không nằm trong URL hay JSON body.
  HTTP client riêng tắt redirect và retry tự động, không có logging interceptor.
- OkHttp SSE xử lý framing/multi-line SSE; bridge dùng channel có backpressure, không bỏ delta.
  Hủy collector đóng EventSource, kể cả khi còn chờ response. Router xử lý trên `Dispatchers.IO`.
- Transport áp dụng deadline coroutine 120 giây cho toàn bộ stream, kể cả lúc chờ header.
  Deadline hoạt động cả khi server tiếp tục gửi heartbeat vì không dựa vào `callTimeout`
  (OkHttp SSE chủ động tắt timeout này sau handshake). Hết hạn trả `TIMEOUT` và hủy HTTP Call;
  cancellation/timeout từ bên gọi vẫn được truyền nguyên trạng.
- Không tự fallback/retry. Bên gọi quyết định thử lại; không tạo request thứ hai ngầm khi gặp lỗi.

## Usage và chi phí chưa biết

Chunk cuối có `TokenUsage` khi nhận được counters cuối hợp lệ. OpenAI lấy
`input_tokens`/`output_tokens`; Gemini lấy `promptTokenCount` và tổng
`candidatesTokenCount + thoughtsTokenCount` khi có, không cộng nhiều lần usage cumulative.
Nếu API không trả counters cuối, không dựng usage từ độ dài text hoặc số liệu giữa stream.

**`TokenUsage.costUsd = Double.NaN` biểu thị chi phí chưa biết**, không phải 0 USD hay miễn phí.
API không cung cấp hóa đơn USD trong các response này; #52 không hard-code bảng giá hoặc bịa số tiền.
Contract `core/model` được giữ nguyên. Bên tiêu thụ phải kiểm tra `costUsd.isFinite()` trước khi
định dạng, lưu hoặc serialize chi phí; khi chưa biết, dùng trạng thái chưa xác định ở tầng dữ liệu/UI.
Không serialize NaN như một số JSON hợp lệ và không chuyển thành 0 để cộng tổng chi phí.

## Lỗi và kiểm thử

`LlmException.failure` là mã nội bộ cho tầng gọi ánh xạ resource EN/VI, không phải câu chữ UI.
HTTP 401, 403, 429, 5xx lần lượt là `AUTHENTICATION`, `PERMISSION`, `RATE_LIMIT`, `SERVER`.
Lỗi mạng, hết deadline stream (`TIMEOUT`), JSON sai, stream bị cắt, nội dung bị chặn và response rỗng có mã riêng.
Không đính kèm provider body, key, prompt hoặc exception gốc vào lỗi công khai.

Test tại `app/src/test/java/com/lttbdd/socialagent/data/ai/` dùng transport/EventSource giả,
key giả được sinh khi chạy; không gọi API thật. Chạy từ root worktree:

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
```

Chưa có kiểm thử gọi dịch vụ thật bằng BYOK; quyền truy cập model, quota và chất lượng câu trả lời
vẫn cần xác minh riêng. #53 kiểm tra nguồn secure storage qua router/adapter bằng transport giả,
không gọi API LLM thật trong CI.

## Tài liệu API chính thức

- [OpenAI Responses streaming](https://developers.openai.com/api/docs/guides/streaming-responses)
- [OpenAI Responses events](https://developers.openai.com/api/reference/resources/responses/streaming-events)
- [Gemini GenerateContent và usage](https://ai.google.dev/api/generate-content)
- [Gemini API và authentication](https://ai.google.dev/api)

## Chọn model theo loại việc — Issue #54

`LlmModule` cấp `TaskModelResolver` làm `LlmConfigurationSource` (thay `InMemoryLlmConfiguration`).
Lựa chọn lưu bền vững trong DataStore (`social_agent_task_models`), chỉ gồm tên provider và model ID.

- **Mặc định:** `WRITE_POST` = Gemini `gemini-2.5-flash`, `CLASSIFY_COMMENT` = Gemini `gemini-2.5-flash-lite`
  (theo `docs/research-llm.md` mục 3.2). `GENERATE_IMAGE` không có mặc định: chưa chọn nghĩa là dùng template ảnh offline.
- **Trạng thái `TaskModelResolution`:** `Ready`, `NeedsApiKey` (provider chưa có key hoặc key đã xóa),
  `NotSelected` (chỉ sinh ảnh), `InvalidSelection` (model đã lưu không hợp lệ cho loại việc). Không có trạng thái nào
  tự chuyển sang provider khác; router vẫn báo `MISSING_API_KEY` đúng nguyên nhân.
- **Kiểm tra model:** `LlmModelCatalog.validate` kiểm tra định dạng ID (tối đa 128 ký tự) bằng
  `isValidModelIdFormat`, dùng chung với `RoutingLlmClient` nên ID lưu được thì router cũng gọi được. Gemini: bỏ
  tiền tố `models/` rồi chỉ nhận `A-Za-z0-9._-`; provider khác nhận thêm `:` và `/` (model tinh chỉnh).
- **Khả năng dùng cho loại việc:** model trong danh mục dùng khai báo sẵn, sai loại việc thì **chặn**
  (`INCOMPATIBLE_WITH_TASK`). Model ngoài danh mục **không bị chặn**, chỉ trả cảnh báo
  `ModelWarning.POSSIBLY_INCOMPATIBLE_WITH_TASK` (vẫn lưu, UI hiện `models_msg_saved_warning`) khi tên không khớp loại
  việc. Dấu hiệu model ảnh: ID chứa `image`, `imagen` hoặc `dall-e`. Đây là heuristic, không xác nhận tài khoản có
  quyền dùng model.
- **Key:** `ProviderKeyAvailability` chỉ hỏi `ApiKeyStore.status(provider) == CONFIGURED`, không đọc nội dung key.
  UI khóa provider chưa có key và dẫn tới màn API key.
- **UI:** `feature/models/` (Cài đặt → Chọn model). Danh mục model gợi ý là cấu hình trong `LlmModelCatalog`,
  cập nhật khi nhà cung cấp đổi model; ID tự nhập vẫn dùng được.
