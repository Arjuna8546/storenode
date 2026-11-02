package com.storenode.domain.shared;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

class IdsTest {

    @Test
    void idsAreVersion7() {
        UUID id = Ids.newId();

        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    void idsAreUnique() {
        Set<UUID> seen = new HashSet<>();
        for (int i = 0; i < 100_000; i++) {
            seen.add(Ids.newId());
        }

        assertThat(seen).hasSize(100_000);
    }

    @Test
    void theFirst48BitsAreTheCreationTime() {
        long before = System.currentTimeMillis();
        UUID id = Ids.newId();
        long after = System.currentTimeMillis();

        long embeddedMillis = id.getMostSignificantBits() >>> 16;

        assertThat(embeddedMillis).isBetween(before, after);
    }

    @Test
    void laterIdsSortAfterEarlierOnes() throws InterruptedException {
        UUID first = Ids.newId();
        Thread.sleep(2);
        UUID second = Ids.newId();

        assertThat(second).isGreaterThan(first);
    }
}

