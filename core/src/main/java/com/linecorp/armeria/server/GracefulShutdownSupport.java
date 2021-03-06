/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server;

import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.common.base.Ticker;

/**
 * Keeps track of pending requests to allow shutdown to happen after a fixed quiet period passes
 * after the last pending request.
 */
abstract class GracefulShutdownSupport {

    static GracefulShutdownSupport create(Duration quietPeriod, Executor blockingTaskExecutor) {
        return create(quietPeriod, blockingTaskExecutor, Ticker.systemTicker());
    }

    static GracefulShutdownSupport create(Duration quietPeriod, Executor blockingTaskExecutor, Ticker ticker) {
        return new DefaultGracefulShutdownSupport(quietPeriod, blockingTaskExecutor, ticker);
    }

    static GracefulShutdownSupport createDisabled() {
        return new DisabledGracefulShutdownSupport();
    }

    /**
     * Increases the number of pending responses.
     */
    abstract void inc();

    /**
     * Decreases the number of pending responses.
     */
    abstract void dec();

    /**
     * Returns the number of pending responses.
     */
    abstract int pendingResponses();

    /**
     * Returns {@code true} if the graceful shutdown has started (or finished).
     */
    abstract boolean isShuttingDown();

    /**
     * Indicates the quiet period duration has passed since the last request.
     */
    abstract boolean completedQuietPeriod();

    private static final class DisabledGracefulShutdownSupport extends GracefulShutdownSupport {

        private volatile boolean shuttingDown;

        @Override
        void inc() {}

        @Override
        void dec() {}

        @Override
        int pendingResponses() {
            return 0;
        }

        @Override
        boolean isShuttingDown() {
            return shuttingDown;
        }

        @Override
        boolean completedQuietPeriod() {
            shuttingDown = true;
            return true;
        }
    }

    private static final class DefaultGracefulShutdownSupport extends GracefulShutdownSupport {

        private final long quietPeriodNanos;
        private final Ticker ticker;
        private final Executor blockingTaskExecutor;

        /**
         * NOTE: {@link #updatedLastResTimeNanos} and {@link #lastResTimeNanos} are declared as non-volatile
         *       while using this field as a memory barrier.
         */
        private final AtomicInteger pendingResponses = new AtomicInteger();
        private boolean updatedLastResTimeNanos;
        private long lastResTimeNanos;
        private boolean setShutdownStartTimeNanos;
        private long shutdownStartTimeNanos;

        DefaultGracefulShutdownSupport(Duration quietPeriod, Executor blockingTaskExecutor, Ticker ticker) {
            quietPeriodNanos = quietPeriod.toNanos();
            this.blockingTaskExecutor = blockingTaskExecutor;
            this.ticker = ticker;
        }

        @Override
        void inc() {
            pendingResponses.incrementAndGet();
        }

        @Override
        void dec() {
            lastResTimeNanos = ticker.read();
            updatedLastResTimeNanos = true;
            pendingResponses.decrementAndGet();
        }

        @Override
        int pendingResponses() {
            return pendingResponses.get();
        }

        @Override
        boolean isShuttingDown() {
            return setShutdownStartTimeNanos;
        }

        @Override
        boolean completedQuietPeriod() {
            if (!setShutdownStartTimeNanos) {
                shutdownStartTimeNanos = ticker.read();
                setShutdownStartTimeNanos = true;
            }

            if (pendingResponses.get() != 0 || !completedBlockingTasks()) {
                return false;
            }

            final long shutdownStartTimeNanos = this.shutdownStartTimeNanos;
            final long currentTimeNanos = ticker.read();
            final long duration;
            if (updatedLastResTimeNanos) {
                duration = Math.min(currentTimeNanos - shutdownStartTimeNanos,
                                    currentTimeNanos - lastResTimeNanos);
            } else {
                duration = currentTimeNanos - shutdownStartTimeNanos;
            }

            return duration >= quietPeriodNanos;
        }

        private boolean completedBlockingTasks() {
            if (!(blockingTaskExecutor instanceof ThreadPoolExecutor)) {
                // Cannot determine if there's a blocking task.
                return true;
            }

            final ThreadPoolExecutor threadPool = (ThreadPoolExecutor) blockingTaskExecutor;
            return threadPool.getQueue().isEmpty() && threadPool.getActiveCount() == 0;
        }
    }
}
