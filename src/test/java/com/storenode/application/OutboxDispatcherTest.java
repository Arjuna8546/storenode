package com.storenode.application;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxDispatcherTest {

    @Test
    void waitsLongerAfterEveryFailureButNeverMoreThanFiveMinutes() {
        assertThat(OutboxDispatcher.backoff(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(OutboxDispatcher.backoff(2)).isEqualTo(Duration.ofSeconds(2));
        assertThat(OutboxDispatcher.backoff(3)).isEqualTo(Duration.ofSeconds(4));
        assertThat(OutboxDispatcher.backoff(9)).isEqualTo(Duration.ofSeconds(256));
        assertThat(OutboxDispatcher.backoff(10)).isEqualTo(Duration.ofMinutes(5));
        assertThat(OutboxDispatcher.backoff(500)).isEqualTo(Duration.ofMinutes(5));
    }
}
