# Facility Service

Service sở hữu facility/court/category/amenity, maintenance, media MinIO, private review documents và additional-facility approval. Owner CRUD tạo DRAFT; hồ sơ PENDING bị khóa chỉnh sửa, Admin approve mới công khai ACTIVE. Submitted documents được giữ lại theo immutable snapshot khi Owner bổ sung hồ sơ.

Public discovery metadata chỉ trả ACTIVE facility có enabled court thuộc active category. Lịch/giá thuộc Schedule; reservation/availability và kết quả tìm sân tổng hợp thuộc Booking. Không đọc database service khác. Equipment catalogue và first-facility Owner application còn là capability riêng chưa hoàn tất.
