# 주문-결제 아키텍처 다이어그램

## 실제 구현 구조

```
[Client]
   |
   v
[Order Service] ──────────────────────────────────────┐
   |                                                    |
   | @Transactional 시작                               |
   |                                                    |
   ├─▶ [Order DB]                                      |
   |      ├─ Order 엔티티 저장                         |
   |      ├─ OrderItem 엔티티 저장                     |
   |      └─ OrderEventOutbox 저장 (PENDING)           |
   |                                                    |
   ├─▶ [TransactionTracing] (REQUIRES_NEW)            |
   |      └─ 보상 트랜잭션 추적용                      |
   |                                                    |
   ├─▶ [ProductServiceClient] (Order Service 내부)    |
   |      │ Circuit Breaker + Retry 적용                |
   |      │                                             |
   |      │ decreaseInventory()                        |
   |      │                                             |
   |      └─▶ HTTP API 호출                            |
   |            │                                       |
   |            v                                       |
   |      [Product Service]                            |
   |            │                                       |
   |            └─▶ [Product DB]                       |
   |                  ├─ products 테이블               |
   |                  │   └─ quantity 필드             |
   |                  │       └─ PESSIMISTIC_WRITE Lock|
   |                  │           └─ 재고 차감          |
   |                  └─ inventory_history 테이블      |
   |                      └─ 재고 변동 이력 저장        |
   |                                                    |
   ├─▶ [PaymentServiceClient] (Order Service 내부)    |
   |      │ Circuit Breaker + Retry 적용                |
   |      │                                             |
   |      │ requestDepositPayment()                    |
   |      │ / requestTossPayment()                     |
   |      │                                             |
   |      └─▶ HTTP API 호출                            |
   |            │                                       |
   |            v                                       |
   |      [Payment Service]                            |
   |            │                                       |
   |            ├─▶ [Payment DB]                      |
   |            |      └─ Payment 엔티티 저장          |
   |            │                                       |
   |            └─▶ [External Payment API] (Toss)    |
   |                  └─ 결제 승인                      |
   |                                                    |
   └─▶ 트랜잭션 커밋                                    |
         │                                              |
         ├─ Order DB 커밋                              |
         │   ├─ Order 상태: COMPLETED                  |
         │   └─ OrderEventOutbox 저장 (PENDING 상태)  |
         │       └─ (별도 스케줄러가 나중에 Kafka 발행) |
```

## 이벤트 발행 흐름 (Outbox 패턴)

```
[Order DB]
   │
   └─▶ [OrderEventOutbox] (PENDING 상태)
         │
         │ 트랜잭션 커밋 후
         │
         v
[Outbox Scheduler] (5분마다 실행)
   │
   ├─▶ PENDING 이벤트 조회
   │
   ├─▶ [Kafka] 이벤트 발행
   │      ├─ order-event (주문 완료)
   │      ├─ payment-refund-request-event (환불 요청)
   │      └─ inventory-event (재고 롤백)
   │
   └─▶ Outbox 상태 업데이트 (PUBLISHED)
         │
         v
   [Settlement Service] (이벤트 소비)
```

## 보상 트랜잭션 흐름

```
[결제 실패 / 주문 취소]
   │
   v
[Order Service]
   │
   ├─▶ [Order DB]
   │      └─ InventoryRollbackEventOutbox 저장 (PENDING)
   │
   ├─▶ [TransactionTracing] (REQUIRES_NEW)
   │      └─ compensationFailed() 상태 저장
   │
   └─▶ [Compensation Retry Scheduler] (10분마다 실행)
         │
         ├─▶ 실패한 보상 로직 재시도
         │
         ├─▶ 재고 롤백 이벤트 발행 (Outbox → Kafka)
         │      │
         │      v
         │   [Product Service]
         │      └─ 재고 복구
         │
         └─▶ 환불 요청 이벤트 발행 (주문 완료 상태인 경우)
               │
               v
            [Payment Service]
               └─ 환불 처리
```

## 상세 흐름 설명

### 1. 주문 생성 단계

1. **주문 데이터 저장**
   - Order, OrderItem 엔티티를 Order DB에 저장
   - TransactionTracing 생성 (별도 트랜잭션, REQUIRES_NEW)

2. **재고 차감**
   - Order Service → Product Service API 호출 (동기)
   - Circuit Breaker + Retry 적용
   - Product Service의 Product DB에서:
     - `products` 테이블의 `quantity` 필드를 비관적 락(PESSIMISTIC_WRITE)으로 차감
     - `inventory_history` 테이블에 재고 변동 이력 저장
   - 실패 시 성공한 재고 차감 롤백

3. **결제 처리**
   - 예치금 결제: Order Service → Payment Service API 호출 (동기)
   - 토스 결제: 주문만 생성하고 상태는 CREATED 유지
   - Circuit Breaker + Retry 적용
   - Payment Service → External Payment API (Toss) 호출

4. **이벤트 저장**
   - 주문 상태가 COMPLETED가 되면 OrderEvent를 OrderEventOutbox에 저장
   - Outbox 상태는 PENDING으로 저장 (아직 Kafka에 발행되지 않음)
   - 트랜잭션 커밋 시점에 Outbox도 함께 커밋
   - 별도 스케줄러가 PENDING 상태의 이벤트를 읽어 Kafka에 발행 후 PUBLISHED로 변경

### 2. 이벤트 발행 (Outbox 패턴)

- **Outbox Scheduler**: 5분마다 실행
- PENDING 상태의 이벤트를 조회하여 Kafka에 발행
- 발행 성공 시 PUBLISHED 상태로 변경
- 실패 시 재시도 횟수 증가, 최대 횟수 초과 시 FAILED 상태

### 3. 보상 트랜잭션

- **결제 실패 시**:
  - 재고 롤백 이벤트를 InventoryRollbackEventOutbox에 저장
  - TransactionTracing에 실패 상태 기록

- **보상 재시도**:
  - Compensation Retry Scheduler가 10분마다 실행
  - 실패한 보상 로직을 재시도 (최대 5회)
  - 재고 롤백 + 환불 요청 처리

## 기술 스택

- **트랜잭션 관리**: Spring @Transactional
- **분산 트랜잭션 추적**: TransactionTracing (REQUIRES_NEW)
- **이벤트 발행**: Outbox 패턴 + Kafka
- **회로 차단기**: Resilience4j Circuit Breaker
- **재시도**: Resilience4j Retry
- **동시성 제어**: Pessimistic Lock (PESSIMISTIC_WRITE)
- **비동기 처리**: Kafka + Outbox Scheduler

