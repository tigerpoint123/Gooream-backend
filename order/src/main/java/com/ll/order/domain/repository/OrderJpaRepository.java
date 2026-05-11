package com.ll.order.domain.repository;

import com.ll.order.domain.model.dto.OrderWithItems;
import com.ll.order.domain.model.entity.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OrderJpaRepository extends JpaRepository<Order, Long> {

    Order findByCode(String code);

    @Query("""
            SELECT new com.ll.order.domain.model.dto.OrderWithItemsProjection(
                o.id, o.orderStatus, o.totalPrice,
                oi.id, oi.code, oi.productId, oi.productCode,
                oi.sellerCode, oi.productName, oi.quantity, oi.price
            )
            FROM Order o
            LEFT JOIN OrderItem oi ON oi.order.id = o.id
            WHERE o.code = :orderCode
            """)
    List<OrderWithItems> findOrderItemsByCode(@Param("orderCode") String orderCode);

    Page<Order> findByBuyerId(Long buyerId, Pageable pageable);

    @Query("""
               SELECT DISTINCT o FROM Order o
               JOIN OrderItem oi ON oi.order = o
               WHERE o.buyerId = :buyerId
               AND oi.productName LIKE %:keyword%
            """)
    Page<Order> findByBuyerIdAndProductNameContaining(@Param("buyerId") Long buyerId,
                                                      @Param("keyword") String keyword,
                                                      Pageable pageable);


}
