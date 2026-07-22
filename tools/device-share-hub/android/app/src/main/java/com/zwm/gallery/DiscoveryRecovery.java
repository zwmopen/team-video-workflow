package com.zwm.gallery;

final class DiscoveryRecovery {
    private DiscoveryRecovery() {
    }

    @FunctionalInterface
    interface Running {
        boolean get();
    }

    @FunctionalInterface
    interface Session {
        void run() throws Exception;
    }

    @FunctionalInterface
    interface FailureHandler {
        void handle(Exception error);
    }

    @FunctionalInterface
    interface Waiter {
        void await(long delayMs) throws InterruptedException;
    }

    static void run(Running running, Session session, FailureHandler failureHandler, Waiter waiter) {
        while (running.get()) {
            try {
                session.run();
            } catch (Exception error) {
                if (!running.get()) return;
                failureHandler.handle(error);
                try {
                    waiter.await(1200L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }
}
