package com.ll.order.domain.model.enums.order;

public enum OrderStatus {
    CREATED,
    CANCELLED,
    PAID,       // 미사용: enum 정의/canTransitionTo 외 실제 상태 전이·비교 코드 없음
    DELIVERY,   // 미사용: enum 정의/canTransitionTo 외 실제 상태 전이·비교 코드 없음
    COMPLETED,
    REFUNDED,   // 미사용: canTransitionTo에서만 등장, 직접 비교/할당하는 코드 없음 (환불은 PaymentRefundNotificationStatus 사용)
    FAILED;

    public boolean canTransitionTo(OrderStatus target) {
        return switch (this) {
            case CREATED -> target == PAID || target == CANCELLED || target == FAILED;
            case PAID -> target == DELIVERY || target == CANCELLED || target == REFUNDED;
            case DELIVERY -> target == COMPLETED || target == REFUNDED;
            case COMPLETED -> target == REFUNDED || target == CANCELLED;
            case CANCELLED, REFUNDED -> false;
            case FAILED -> false;
        };
    }
}
