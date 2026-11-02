package com.storenode.application.port;

import java.time.LocalDate;

public interface FiscalSequence {

    /** The next sequence number for this branch on this business day. */
    int next(String branchCode, LocalDate businessDate);
}