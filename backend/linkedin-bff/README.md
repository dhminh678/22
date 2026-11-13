# LinkedIn BFF (Social Agent)

BFF tối giản cho đăng nhập LinkedIn (Standard OAuth 2.0). `client_secret` chỉ nằm ở máy chủ này, không nằm trong APK.
Thiết kế: `docs/research-social-api.md` (Luồng B). Không có dependency; cần Node.js 18 trở lên.

## Endpoint
| Method | Đường dẫn | Việc |
|---|---|---|
| POST | `/auth/linkedin/start` | App đăng ký `{state, code_challenge}` (phiên sống 10 phút, dùng một lần) |
| GET | `/auth/linkedin/callback` | LinkedIn chuyển về đây; BFF đổi `code` lấy token rồi 302 về app kèm `exchange_code` + `state` |
| POST | `/auth/linkedin/token` | App gửi `{exchange_code, state, code_verifier}`; nhận `{access_token, token_type, expires_in, scope}` một lần (mã sống 60 giây) |
| GET | `/healthz` | Kiểm tra sống |

BFF không chuyển tiếp `refresh_token`, không có refresh/logout, không ghi log token/code/state/verifier.

## Cấu hình (biến môi trường)
| Biến | Ý nghĩa |
|---|---|
| `LINKEDIN_MEMBER_CLIENT_ID` | Client ID của App Member (công khai) |
| `LINKEDIN_MEMBER_CLIENT_SECRET` | Primary Client Secret của App Member. **Chỉ đặt trên máy chủ.** |
| `BFF_PUBLIC_URL` | URL HTTPS công khai của BFF, không có `/` cuối |
| `APP_REDIRECT_URI` | Mặc định `com.lttbdd.socialagent://oauth/linkedin/callback` (trùng Manifest) |
| `PORT` | Mặc định 8787 |

Redirect URL phải đăng ký ở LinkedIn Developer Portal (tab Auth của App Member): `{BFF_PUBLIC_URL}/auth/linkedin/callback`.

## Chạy và test
```bash
cd backend/linkedin-bff
npm test                # 18 test, LinkedIn được giả lập, không cần mạng
LINKEDIN_MEMBER_CLIENT_ID=... LINKEDIN_MEMBER_CLIENT_SECRET=... BFF_PUBLIC_URL=https://... npm start
```

## Lưu ý vận hành
- Phiên và mã nằm trong **bộ nhớ một tiến trình**: chạy đúng **một instance** (serverless nhiều instance sẽ làm callback/token rơi vào instance khác). Cần nhiều instance thì đổi sang kho dùng chung (Redis).
- Hosting free có thể "ngủ": lần gọi đầu chậm; phiên start→callback phải xảy ra trên cùng instance đang chạy.
- Chỉ phục vụ qua HTTPS (app từ chối `http://`).
