package com.storenode.integration;

import com.storenode.application.port.FiscalSequence;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FiscalSequenceIntegrationTest extends IntegrationTest {

    @Autowired
    private FiscalSequence sequence;

    @Test
    void numbersRestartEachDayAndEveryBranchHasItsOwnCount() {
        LocalDate monday = LocalDate.of(2026, 11, 2);
        LocalDate tuesday = monday.plusDays(1);

        assertThat(sequence.next("AIR01", monday)).isEqualTo(1);
        assertThat(sequence.next("AIR01", monday)).isEqualTo(2);
        assertThat(sequence.next("AIR01", tuesday)).isEqualTo(1);   // a new day starts again at 1
        assertThat(sequence.next("BLR02", monday)).isEqualTo(1);    // another branch has its own count
        assertThat(sequence.next("AIR01", monday)).isEqualTo(3);    // and the first one carries on
    }
}

