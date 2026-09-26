# Unit Test Cases & Report — MẪU

**Dự án:** Nền tảng lớp học trực tuyến · **Phiên bản:** 0.1 · **Trạng thái:** BẢN NHÁP · **Ngày:** 23/09/2026

> Tài liệu thiết kế theo thông tin chủ sản phẩm đã cung cấp. Các mục `[CẦN CHỐT]`, `[ĐIỀN KHI THỰC HIỆN]` chưa được xác nhận. Bản nháp không phải biên bản đã ký, kết quả kiểm thử hay cam kết dịch vụ.

## Bộ ca unit test cần viết
| ID | Đối tượng | Input/kỳ vọng |
|---|---|---|
| UT-01 | `AccessPolicy` | OWNER của lớp A xem mọi khóa A, bị từ chối khóa B |
| UT-02 | `StaffPolicy` | STAFF chỉ có EXAM_GRADE không sửa giá hoặc quyền staff |
| UT-03 | `ProPolicy` | entitlement còn hạn → PRO; hết hạn/hoàn tiền → FREE; mua khóa A không mở khóa B |
| UT-04 | `ExamAudience` | COURSE_A từ chối người chỉ mua B; PRO cho mọi PRO; AND segment đúng |
| UT-05 | `ExamScoring` | ngưỡng biên, làm nhiều lần, chỉ kết quả công bố được tính, sửa điểm không cộng trùng |
| UT-06 | `OrderIdempotency` | webhook lặp/sai chữ ký không nhân đôi entitlement |
| UT-07 | `SegmentParser` | whitelist operator, không nhận biểu thức SQL/điều kiện nguy hiểm |

## Mẫu ghi nhận kết quả
| Build/commit | Ngày | Tổng | Pass | Fail | Skip | Báo cáo CI | Người xác nhận |
|---|---|---:|---:|---:|---:|---|---|
| `[ĐIỀN KHI CHẠY]` |  |  |  |  |  |  |  |

**Hiện trạng:** Chưa có code mới và chưa chạy unit test; mọi ô kết quả để trống.
