# Quy trình làm việc với Git & GitHub

## Vòng đời một ticket

```
Ticket (Todo) → branch → commit… → push → PR (In Review) → 2 approve → Merge (Done)
```

1. **Ticket** trên GitHub Project phải đủ: Title, Description, Acceptance criteria, Solution,
   Estimate (giờ), Assignee, Milestone. Bắt đầu làm thì kéo sang **In Progress**.
2. **Tạo branch** từ `main` mới nhất:
   ```bash
   git checkout main
   git pull
   git checkout -b feature/12-oauth-facebook
   ```
3. **Commit nhỏ, thường xuyên**:
   ```bash
   git commit -m "feat(#12): add Facebook login screen"
   ```
   | type | Dùng khi |
   |---|---|
   | `feat` | Thêm tính năng |
   | `fix` | Sửa lỗi |
   | `docs` | Tài liệu |
   | `refactor` | Sửa cấu trúc code, không đổi hành vi |
   | `test` | Thêm/sửa test |
   | `chore` | Cấu hình, build, việc vặt |
4. **Cập nhật `main` vào branch** trước khi mở PR, xử lý conflict nếu có:
   ```bash
   git pull origin main
   ```
5. **Push và mở PR** vào `main`:
   ```bash
   git push -u origin feature/12-oauth-facebook
   ```
   Điền đủ PR template, ghi `Closes #12`, kéo ticket sang **In Review**.
   2 reviewer được **tự động gắn** theo vòng xoay (xem bên dưới).
6. **Review**: đọc code, chạy thử, để comment cụ thể, rồi chọn **Approve** hoặc **Request changes**.
   Người làm sửa bằng commit mới trên cùng branch, sau đó bấm **Re-request review** (biểu tượng ↻ cạnh tên reviewer).
7. **Merge** khi đủ **2 approve** và CI xanh, dùng kiểu **Create a merge commit**. Tác giả tự merge PR của mình;
   branch được GitHub tự xoá sau khi merge.

## Quy tắc

- Không push thẳng vào `main` (branch protection chặn).
- Không tự merge PR khi chưa đủ 2 approve.
- Mỗi PR một ticket, cố gắng dưới ~400 dòng thay đổi.
- Mỗi tuần mỗi người phải có PR của mình **và** review PR của người khác — điểm cá nhân tính theo đó.

## Thời hạn review và merge

PR chỉ merge được khi đủ approve **và** không còn Request changes. Comment không gỡ chặn được PR,
nên mỗi bước dưới đây đều có hạn:

| Việc | Ai làm | Hạn |
|---|---|---|
| Mở PR cho việc của tuần | Tác giả | **Thứ Năm** (để còn thứ Sáu, thứ Bảy cho review và sửa) |
| Review lần đầu, chọn **Approve** hoặc **Request changes** (không chỉ Comment) | Reviewer được gắn | **24 giờ** sau khi được gắn |
| Sửa theo review rồi bấm **Re-request review** | Tác giả | Càng sớm càng tốt |
| Xem lại và Approve (hoặc nêu rõ còn thiếu gì) | Người đã Request changes | **24 giờ** sau khi được re-request |
| Merge | Tác giả | **24 giờ** sau khi đủ 2 approve và CI xanh |

Quá hạn thì nhắc trực tiếp trong nhóm chat. Nếu vẫn không có phản hồi sau **48 giờ** kể từ lúc được nhắc:

- **Đã Request changes, tác giả đã sửa và re-request, nhưng người đó không xem lại:** chủ repo **Dismiss review**
  đang chặn, ghi rõ lý do trong PR (đã sửa những điểm nào, đã nhắc lúc nào).
- **Chưa từng review** (không có review nào để dismiss): tác giả gỡ người đó khỏi danh sách Reviewers
  và gắn thành viên còn lại review thay.

Cả hai trường hợp PR vẫn phải đủ 2 approve như bình thường.

## Reviewer xoay vòng

Workflow [`assign-reviewers.yml`](../.github/workflows/assign-reviewers.yml) tự gắn 2 reviewer khi mở PR
(hoặc khi chuyển PR draft sang "Ready for review") và gán tác giả làm assignee:

| Tác giả | Reviewer |
|---|---|
| Bthangnguyen (A) | dhminh678, sangcotdo2407 |
| dhminh678 (B) | sangcotdo2407, quaanluuw0210 |
| sangcotdo2407 (C) | quaanluuw0210, Bthangnguyen |
| quaanluuw0210 (D) | Bthangnguyen, dhminh678 |

Mỗi người review đúng 2 người khác. Reviewer bận thì có thể nhờ người còn lại thay, miễn đủ 2 approve.

## Viết review tốt

- Nêu **vấn đề + lý do + gợi ý**: "Hàm này gọi API trên main thread → app sẽ đơ, nên chuyển sang `viewModelScope.launch`."
- Phân biệt mức độ: `[bắt buộc]` phải sửa mới approve, `[gợi ý]` có thể để sau, `[hỏi]` cần giải thích.
- Kiểm tra: đúng acceptance criteria chưa, có test chưa, có chuỗi hard-code không, có đụng `core/` không.
- Chỉ approve khi đã đọc và chạy thử.

## Xử lý conflict

Conflict xảy ra khi hai người sửa **cùng dòng** của cùng file. Luôn xử lý **trên branch của mình**:

```bash
git checkout feature/12-oauth-facebook
git pull origin main
# mở file có <<<<<<< ======= >>>>>>>, chọn bản đúng, xoá các dấu đánh dấu
git add .
git commit -m "fix(#12): resolve conflict with main"
git push
```

## Báo cáo tuần (Slack, tối thứ Bảy)

```
TUẦN <ID>

Current tasks:
* Task 1 <assignee>

Next plan:
* Task 1 <assignee>

Blockers:
* Blocker 1
```
