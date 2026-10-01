-- R13-06 (FR-07/TC-17): "Đơn hàng của tôi" needs to show a REFUNDED order's refund timestamp
-- alongside paidAt; the orders table only tracked paidAt until now.
ALTER TABLE orders ADD COLUMN refunded_at DATETIME(6) NULL AFTER paid_at;
