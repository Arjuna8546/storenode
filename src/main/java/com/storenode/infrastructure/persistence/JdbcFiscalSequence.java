package com.storenode.infrastructure.persistence;

import com.storenode.application.port.FiscalSequence;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;

@Repository
public class JdbcFiscalSequence implements FiscalSequence {

    private final JdbcClient jdbc;

    public JdbcFiscalSequence(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // Simple on purpose: count today's orders and add one. Phase 7 shows why that is not safe.
    @Override
    public int next(String branchCode, LocalDate businessDate) {
        long placedToday = jdbc.sql("select count(*) from orders where business_date = :date")
                .param("date", businessDate)
                .query(Long.class)
                .single();
        return (int) placedToday + 1;
    }
}

