package com.ll.order.domain.model.dto;

import com.ll.order.domain.model.enums.order.OrderStatus;

public record OrderWithItems(
        Long orderId,
        OrderStatus orderStatus,
        Integer totalPrice,
        Long orderItemId,
        String orderItemCode,
        Long productId,
        String productCode,
        String sellerCode,
        String productName,
        Integer quantity,
        Integer price
) {
}

