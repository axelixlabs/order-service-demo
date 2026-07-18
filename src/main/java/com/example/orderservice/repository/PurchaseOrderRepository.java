package com.example.orderservice.repository;

import com.example.orderservice.domain.PurchaseOrder;
import com.example.orderservice.web.dto.OrderCsvRow;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
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
     * Hot path: load a single order with everything the response needs in ONE query.
     * The items collection, each item's product, the customer, payment and shipment
     * are all fetch-joined, so no lazy SELECT fires while the DTO is built — even with
     * open-in-view disabled. Only {@code items} is a collection, so there is no
     * cartesian blow-up (the remaining joins are all to-one).
     */
    @Query("""
            select distinct o from PurchaseOrder o
            left join fetch o.items i
            left join fetch i.product
            join fetch o.customer
            left join fetch o.payment
            left join fetch o.shipment
            where o.id = :id
            """)
    Optional<PurchaseOrder> findByIdWithDetails(@Param("id") Long id);

    /**
     * Heavy report, step 1: page the order ids with real SQL {@code LIMIT}/{@code OFFSET}.
     * No collection is fetched here, so the {@link Pageable} translates straight into
     * pagination in the database (no in-memory paging).
     */
    @QueryHints(@QueryHint(name = org.hibernate.jpa.HibernateHints.HINT_READ_ONLY, value = "true"))
    @Query("""
            select o.id from PurchaseOrder o
            where o.customer.id = :customerId
              and o.createdAt between :from and :to
            """)
    List<Long> findOrderIdsByCustomer(@Param("customerId") Long customerId,
                                      @Param("from") Instant from,
                                      @Param("to") Instant to,
                                      Pageable pageable);

    /**
     * Heavy report, step 2: fetch the full graph for just the page of ids from step 1.
     * There is no {@link Pageable} on this query (the page is already bounded by the id
     * list), so the collection fetch join does not trigger in-memory pagination.
     */
    @QueryHints(@QueryHint(name = org.hibernate.jpa.HibernateHints.HINT_READ_ONLY, value = "true"))
    @Query("""
            select distinct o from PurchaseOrder o
            left join fetch o.items i
            left join fetch i.product
            join fetch o.customer
            left join fetch o.payment
            left join fetch o.shipment
            where o.id in :ids
            order by o.createdAt asc
            """)
    List<PurchaseOrder> findOrdersWithDetailsByIds(@Param("ids") List<Long> ids);

    /**
     * Heavy report: stream one customer's orders in a date window as a flat CSV
     * projection. The customer email and line-item count are resolved in the same
     * SQL query (join + count), so writing a row never lazily loads anything.
     * Must be consumed inside a read-only transaction.
     */
    @QueryHints({
            @QueryHint(name = org.hibernate.jpa.HibernateHints.HINT_FETCH_SIZE, value = "200"),
            @QueryHint(name = org.hibernate.jpa.HibernateHints.HINT_READ_ONLY, value = "true")
    })
    @Query("""
            select new com.example.orderservice.web.dto.OrderCsvRow(
                o.orderNumber, o.status, c.email, count(i), o.totalAmount, o.createdAt)
            from PurchaseOrder o
            join o.customer c
            left join o.items i
            where c.id = :customerId
              and o.createdAt between :from and :to
            group by o.id, o.orderNumber, o.status, c.email, o.totalAmount, o.createdAt
            order by o.createdAt asc
            """)
    Stream<OrderCsvRow> streamOrderCsvRows(@Param("customerId") Long customerId,
                                           @Param("from") Instant from,
                                           @Param("to") Instant to);
}
