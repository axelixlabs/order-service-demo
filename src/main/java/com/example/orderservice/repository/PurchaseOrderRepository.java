package com.example.orderservice.repository;

import com.example.orderservice.domain.OrderStatus;
import com.example.orderservice.domain.PurchaseOrder;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, Long> {

    Optional<PurchaseOrder> findByOrderNumber(String orderNumber);

    /**
     * Hot-path read: fetch a single order together with its items and each
     * item's product in one query to avoid N+1 selects.
     */
    @Query("""
            select distinct o from PurchaseOrder o
            left join fetch o.items i
            left join fetch i.product
            join fetch o.customer
            where o.id = :id
            """)
    Optional<PurchaseOrder> findDetailedById(@Param("id") Long id);

    /**
     * Heavy report: stream all orders in a date window without loading them all
     * into memory at once. Must be consumed inside a read-only transaction.
     */
    @QueryHints({
            @QueryHint(name = org.hibernate.jpa.HibernateHints.HINT_FETCH_SIZE, value = "200"),
            @QueryHint(name = org.hibernate.jpa.HibernateHints.HINT_READ_ONLY, value = "true")
    })
    @Query("""
            select o from PurchaseOrder o
            join fetch o.customer
            where o.createdAt between :from and :to
            order by o.createdAt asc
            """)
    Stream<PurchaseOrder> streamByCreatedAtBetween(@Param("from") Instant from, @Param("to") Instant to);

    /**
     * Heavy report: aggregate units sold and revenue per product/category over a
     * date window. Runs entirely in the database and returns a compact projection.
     */
    @Query("""
            select
                c.id           as categoryId,
                c.name         as categoryName,
                p.id           as productId,
                p.sku          as productSku,
                p.name         as productName,
                sum(i.quantity) as unitsSold,
                sum(i.lineTotal) as grossRevenue,
                count(distinct o.id) as orderCount
            from OrderItem i
            join i.order o
            join i.product p
            join p.category c
            where o.createdAt between :from and :to
              and o.status <> :excluded
            group by c.id, c.name, p.id, p.sku, p.name
            order by sum(i.lineTotal) desc
            """)
    List<SalesSummaryRow> aggregateSales(@Param("from") Instant from,
                                         @Param("to") Instant to,
                                         @Param("excluded") OrderStatus excluded);
}
