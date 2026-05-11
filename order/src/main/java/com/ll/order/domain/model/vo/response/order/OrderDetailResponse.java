package com.ll.order.domain.model.vo.response.order;

import com.ll.order.domain.model.dto.OrderWithItems;
import com.ll.order.domain.model.enums.order.OrderStatus;

import java.util.List;

public record OrderDetailResponse(
        Long orderId,
        OrderStatus status,
        int totalPrice,
        List<OrderItemInfo> orderItems
) {
    public static OrderDetailResponse from(List<OrderWithItems> rows) {
        OrderWithItems first = rows.getFirst();
        return new OrderDetailResponse(
                first.orderId(),
                first.orderStatus(),
                first.totalPrice(),
                rows.stream()
                        .filter(row -> row.orderItemId() != null)
                        .map(OrderItemInfo::from)
                        .toList()
        );
    }

    public record OrderItemInfo(
            Long orderItemId,
            String orderItemCode,
            Long productId,
            String productCode,
            String sellerCode,
            String productName,
            Integer quantity,
            Integer price
    ) {
        private static OrderItemInfo from(OrderWithItems row) {
            return new OrderItemInfo(
                    row.orderItemId(),
                    row.orderItemCode(),
                    row.productId(),
                    row.productCode(),
                    row.sellerCode(),
                    row.productName(),
                    row.quantity(),
                    row.price()
            );
        }
    }
}
