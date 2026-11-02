package com.storenode.domain.order;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.regex.Pattern;

public record OrderNumber(String branchCode, LocalDate businessDate, int sequence) {

    private static final Pattern BRANCH_CODE = Pattern.compile("[A-Z0-9]{2,10}");

    public OrderNumber {
        Objects.requireNonNull(branchCode, "branchCode");
        Objects.requireNonNull(businessDate, "businessDate");
        if (!BRANCH_CODE.matcher(branchCode).matches()) {
            throw new IllegalArgumentException("Branch code must be 2-10 capital letters or digits: " + branchCode);
        }
        if (sequence < 1) {
            throw new IllegalArgumentException("Sequence starts at 1, got " + sequence);
        }
    }

    public String format() {
        return "ORD-%s-%s-%04d".formatted(branchCode, businessDate.format(DateTimeFormatter.BASIC_ISO_DATE), sequence);
    }

    @Override
    public String toString() {
        return format();
    }
}

