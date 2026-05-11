package com.ll.order.domain.service.order;

import com.ll.core.model.exception.BaseException;
import com.ll.order.global.client.ProductServiceClient;
import com.ll.order.global.exception.OrderErrorCode;
import com.ll.order.domain.model.enums.order.OrderStatus;
import com.ll.order.domain.model.enums.product.ProductStatus;
import com.ll.order.domain.model.vo.request.ProductRequest;
import com.ll.order.domain.model.vo.response.order.OrderValidateResponse;
import com.ll.order.domain.model.vo.response.product.ProductResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
@RequiredArgsConstructor
@Slf4j
public class OrderValidator {

    private final ProductServiceClient productServiceClient;

    public void validateOrderStatusChange(OrderStatus current, OrderStatus target) {
        if (!current.canTransitionTo(target)) {
            log.warn("해당 상태로 전환할 수 없습니다. current: {}, target: {}", current, target);
            throw new BaseException(OrderErrorCode.INVALID_ORDER_STATUS_TRANSITION);
        }
    }

    public void validateProductInventory(String productCode, int requestedQuantity) {
        ProductResponse productInfo = getProductInfo(productCode);
        validateProductInventory(productInfo, requestedQuantity);
    }

    public void validateProductInventory(ProductResponse productInfo, int requestedQuantity) {
        // 재고 부족 체크
        if (productInfo.quantity() < requestedQuantity) {
            log.warn("재고가 부족합니다. productCode: {}, 요청 수량: {}, 재고: {}",
                    productInfo.code(), requestedQuantity, productInfo.quantity());
            throw new BaseException(OrderErrorCode.INSUFFICIENT_INVENTORY);
        }

        // 판매 중인지 체크
        if (productInfo.status() == null || productInfo.status() != ProductStatus.ON_SALE) {
            log.warn("판매 중이 아닌 상품입니다. productCode: {}, status: {}",
                    productInfo.code(), productInfo.status());
            throw new BaseException(OrderErrorCode.PRODUCT_NOT_ON_SALE);
        }
    }

    private ProductResponse getProductInfo(String productCode) {
        return Optional.ofNullable(productServiceClient.getProductByCode(productCode))
                .orElseThrow(() -> {
                    log.warn("상품을 찾을 수 없습니다. productCode: {}", productCode);
                    return new BaseException(OrderErrorCode.PRODUCT_NOT_FOUND);
                });
    }
}

