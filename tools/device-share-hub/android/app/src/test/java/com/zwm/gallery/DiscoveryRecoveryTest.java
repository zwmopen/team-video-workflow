package com.zwm.gallery;

import org.junit.Test;

import java.net.SocketException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;

public final class DiscoveryRecoveryTest {
    @Test public void transientNetworkFailureStartsANewDiscoverySession() {
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicInteger sessions = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        AtomicInteger waits = new AtomicInteger();

        DiscoveryRecovery.run(
                running::get,
                () -> {
                    if (sessions.incrementAndGet() == 1) {
                        throw new SocketException("sendto failed: ENETUNREACH");
                    }
                    running.set(false);
                },
                ignored -> failures.incrementAndGet(),
                ignored -> waits.incrementAndGet());

        assertEquals(2, sessions.get());
        assertEquals(1, failures.get());
        assertEquals(1, waits.get());
    }

    @Test public void shutdownSocketErrorDoesNotRetry() {
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicInteger failures = new AtomicInteger();
        AtomicInteger waits = new AtomicInteger();

        DiscoveryRecovery.run(
                running::get,
                () -> {
                    running.set(false);
                    throw new SocketException("Socket closed");
                },
                ignored -> failures.incrementAndGet(),
                ignored -> waits.incrementAndGet());

        assertEquals(0, failures.get());
        assertEquals(0, waits.get());
    }
}
