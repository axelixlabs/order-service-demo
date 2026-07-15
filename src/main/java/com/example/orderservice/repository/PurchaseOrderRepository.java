package com.example.orderservice.repository;

import com.example.orderservice.domain.PurchaseOrder;
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
     * Heavy report: stream one customer's orders in a date window without loading
     * them all into memory at once. Must be consumed inside a read-only transaction.
     *
     * <p>ANTI-PATTERN (demo): this deliberately does NOT {@code join fetch} the
     * customer or items, so {@link com.example.orderservice.service.ReportService}
     * triggers a fresh SELECT per row while writing the CSV — a classic N+1.
     * FIX: add {@code join fetch o.customer} (items can't be fetch-joined here
     * without breaking the streaming cursor; use a batch-size hint or a two-step
     * load instead).
     */
    @QueryHints({
            @QueryHint(name = org.hibernate.jpa.HibernateHints.HINT_FETCH_SIZE, value = "200"),
            @QueryHint(name = org.hibernate.jpa.HibernateHints.HINT_READ_ONLY, value = "true")
    })
    @Query("""
            select o from PurchaseOrder o
            where o.customer.id = :customerId
              and o.createdAt between :from and :to
            order by o.createdAt asc
            """)
    Stream<PurchaseOrder> streamByCustomerIdAndCreatedAtBetween(@Param("customerId") Long customerId,
                                                                @Param("from") Instant from,
                                                                @Param("to") Instant to);

    /**
     * Heavy report: a paginated feed of one customer's orders with line items.
     *
     * <p>ANTI-PATTERN (demo): this combines a collection {@code join fetch} with a
     * {@link Pageable}. Because the join multiplies each order into one row per
     * item, Hibernate cannot express the page as SQL {@code LIMIT}/{@code OFFSET}
     * — so it loads the ENTIRE matching result set and paginates it IN MEMORY,
     * logging {@code HHH000104: firstResult/maxResults specified with collection
     * fetch; applying in memory!}. We write no {@code skip()/limit()} ourselves;
     * Hibernate does the (in-memory) paging. FIX: paginate the root ids first
     * (a plain {@code Page<Long>} query with no fetch), then fetch items for just
     * that page in a second query — or use {@code @BatchSize}/subselect fetching.
     */
    @QueryHints(@QueryHint(name = org.hibernate.jpa.HibernateHints.HINT_READ_ONLY, value = "true"))
    @Query("""
            select distinct o from PurchaseOrder o
            left join fetch o.items
            where o.customer.id = :customerId
              and o.createdAt between :from and :to
            """)
    List<PurchaseOrder> findOrdersWithItemsByCustomerId(@Param("customerId") Long customerId,
                                                        @Param("from") Instant from,
                                                        @Param("to") Instant to,
                                                        Pageable pageable);
}
