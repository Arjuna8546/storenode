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

    // One statement: create today's row with 1, or add 1 to it if it exists, and give back the new value.
    // Postgres locks the row, so a second terminal waits here until the first order commits.
    // That is what makes the numbers one-after-another; the transaction rolling back is what keeps them gap-free.
    @Override
    public int next(String branchCode, LocalDate businessDate) {
        return jdbc.sql("""
                        insert into fiscal_counter (branch_code, business_date, last_number)
                        values (:branch, :date, 1)
                        on conflict (branch_code, business_date)
                        do update set last_number = fiscal_counter.last_number + 1
                        returning last_number""")
                .param("branch", branchCode)
                .param("date", businessDate)
                .query(Integer.class)
                .single();
    }
}

