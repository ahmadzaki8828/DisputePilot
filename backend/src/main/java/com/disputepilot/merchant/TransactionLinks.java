package com.disputepilot.merchant;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Maps a real sandbox seller transaction to a seeded order ({@code transaction_links}, migration V300). */
@Repository
public class TransactionLinks {

    private final JdbcClient jdbc;

    public TransactionLinks(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<String> orderIdFor(String sellerTransactionId) {
        return jdbc.sql("select order_id from transaction_links where seller_transaction_id = :t")
                .param("t", sellerTransactionId).query(String.class).optional();
    }

    public void link(String sellerTransactionId, String orderId, String linkedBy) {
        jdbc.sql("""
                insert into transaction_links (seller_transaction_id, order_id, linked_by)
                values (:t, :o, :by)
                on conflict (seller_transaction_id) do update set order_id = excluded.order_id,
                    linked_by = excluded.linked_by, created_at = now()
                """).param("t", sellerTransactionId).param("o", orderId).param("by", linkedBy).update();
    }
}
