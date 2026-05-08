# Migration cơ sở dữ liệu

Project hiện tại chưa dùng relational database nên chưa bật Flyway/Liquibase.

Khi bổ sung database, khuyến nghị:

1. Thêm dependency `org.flywaydb:flyway-core`.
2. Đặt migration versioned tại thư mục này, ví dụ `V1__init_schema.sql`.
3. Chạy migration trên staging trước production.
4. Không chỉnh sửa migration đã chạy ở môi trường shared.
