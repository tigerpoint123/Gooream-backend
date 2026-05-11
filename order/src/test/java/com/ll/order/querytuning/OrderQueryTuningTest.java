package com.ll.order.querytuning;

import com.ll.order.domain.model.dto.OrderWithItems;
import com.ll.order.domain.model.entity.Order;
import com.ll.order.domain.model.entity.OrderItem;
import com.ll.order.domain.model.enums.order.OrderType;
import com.ll.order.integration.success.BaseOrderIntegrationSuccessTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
@DisplayName("주문 조회 쿼리 튜닝")
class OrderQueryTuningTest extends BaseOrderIntegrationSuccessTest {

    private static final int DUMMY_ORDER_COUNT = 100;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void createDummyOrdersAndOrderItemsBeforeEach() {
        createDummyOrdersWithTwoItemsEach();
    }

    @DisplayName("order, orderItem 조회 성능 비교 - 주문 상세 조회")
    @Test
    void compareEachSelectOrJoinSelect() throws Exception {
        TransactionTemplate requiresNew = new TransactionTemplate(transactionManager);
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        String orderCode = requiresNew.execute(status -> {
            Order order = Order.create(1L, "USER-001", OrderType.ONLINE, "서울시 강남구");
            Order saved = orderJpaRepository.save(order);
            OrderItem item1 = saved.createOrderItem(1L, "PROD-001", "SELLER-001", "노트북", 1, 10000);
            OrderItem item2 = saved.createOrderItem(2L, "PROD-002", "SELLER-001", "마우스", 2, 5000);
            orderItemJpaRepository.saveAll(List.of(item1, item2));
            orderJpaRepository.save(saved);
            return saved.getCode();
        });

        TransactionTemplate readOnlyNew = new TransactionTemplate(transactionManager);
        readOnlyNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        readOnlyNew.setReadOnly(true);

        final String code = orderCode;

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {

            Future<TimedReadResult> splitFuture = executor.submit(() -> readOnlyNew.execute(status -> {
                long t0 = System.nanoTime();
                Order o = orderJpaRepository.findByCode(code);
                assertThat(o).isNotNull();
                List<OrderItem> items = orderItemJpaRepository.findByOrderId(o.getId());
                long queryNanos = System.nanoTime() - t0;
                return new TimedReadResult("split(findByCode+findByOrderId)", o.getId(), items, queryNanos);
            }));

            Future<TimedReadResult> joinFuture = executor.submit(() -> readOnlyNew.execute(status -> {
                long t0 = System.nanoTime();
                List<OrderWithItems> rows = orderJpaRepository.findOrderItemsByCode(code);
                long queryNanos = System.nanoTime() - t0;
                assertThat(rows).isNotEmpty();
                long orderId = rows.getFirst().orderId();
                List<OrderItem> items = orderItemJpaRepository.findByOrderId(orderId);
                return new TimedReadResult("join(findOrderItemsByCode)", orderId, items, queryNanos);
            }));

            TimedReadResult split = splitFuture.get();
            TimedReadResult join = joinFuture.get();

            assertThat(join.orderId()).isEqualTo(split.orderId());
            assertThat(split.items()).hasSize(2);

            List<String> splitCodes = split.items().stream().map(OrderItem::getProductCode).sorted().toList();
            List<String> joinCodes = join.items().stream().map(OrderItem::getProductCode).sorted().toList();
            assertThat(joinCodes).isEqualTo(splitCodes);

            double splitMs = split.queryNanos() / 1_000_000.0;
            double joinMs = join.queryNanos() / 1_000_000.0;
            double ratioSplitOverJoin = join.queryNanos() == 0
                    ? Double.NaN
                    : (double) split.queryNanos() / join.queryNanos();

            log.info(
                    """
                            [Query tuning] concurrent read perf | orderCode={}
                              {} : {} ms ({} ns)
                              {} : {} ms ({} ns)
                              split/join wall-time ratio : {}x
                            """,
                    code,
                    split.label(), splitMs, split.queryNanos(),
                    join.label(), joinMs, join.queryNanos(),
                    Double.isNaN(ratioSplitOverJoin) ? "n/a" : String.format("%.2f", ratioSplitOverJoin));
        }
    }

    private void createDummyOrdersWithTwoItemsEach() {
        for (int i = 0; i < OrderQueryTuningTest.DUMMY_ORDER_COUNT; i++) {
            Order order = Order.create(1L, "USER-001", OrderType.ONLINE, "더미주소-" + i);
            Order saved = orderJpaRepository.save(order);
            OrderItem item1 = saved.createOrderItem(
                    1L, "PROD-001", "SELLER-001", "더미상품A-" + i, 1, 1000);
            OrderItem item2 = saved.createOrderItem(
                    2L, "PROD-002", "SELLER-001", "더미상품B-" + i, 1, 2000);
            orderItemJpaRepository.saveAll(List.of(item1, item2));
            orderJpaRepository.save(saved);
        }
    }

    private record TimedReadResult(String label, long orderId, List<OrderItem> items, long queryNanos) {
    }
}
