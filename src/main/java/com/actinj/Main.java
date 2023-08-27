package com.actinj;

import com.actinj.supervision.ChildSpec;
import com.actinj.supervision.Strategy;
import com.actinj.supervision.Supervisor;
import com.actinj.supervision.SupervisorFlags;
import com.actinj.supervision.Restart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);
    final static Runnable runnable = () -> {
        try {
            int counter = 0;
            while (true) {
                logger.info("Tick {}", ++counter);
                Thread.sleep(Duration.ofSeconds(1));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    };
    final static Runnable crashingRunnable = () -> {
        try {
            while (true) {
                Thread.sleep(Duration.ofSeconds(1));
                throw new RuntimeException("I'm done with this");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    };

    public static void main(String[] args) throws InterruptedException {
        final Supervisor supervisor = getSupervisor();
        Thread.ofVirtual().start(supervisor);
        Thread.sleep(Duration.ofSeconds(10));
        supervisor.stop();
        Thread.sleep(Duration.ofSeconds(10));
    }

    private static Supervisor getSupervisor() {
        final AtomicInteger threadCounter = new AtomicInteger(0);
        final Supplier<ChildSpec> childSupplier = () -> new ChildSpec("Child_" + threadCounter.incrementAndGet(),
                runnable, Restart.TRANSIENT);
        final Supplier<ChildSpec> crashSupplier = () -> new ChildSpec("Child_" + threadCounter.incrementAndGet(),
                crashingRunnable, Restart.TRANSIENT);

        final Supervisor supervisor2 = new Supervisor("sup2",
                new SupervisorFlags(Strategy.ONE_FOR_ONE, 5, Duration.ofSeconds(10)),
                Arrays.asList(childSupplier, crashSupplier, childSupplier));
        final Supervisor supervisor = new Supervisor("sup1",
                new SupervisorFlags(Strategy.ONE_FOR_ONE, 5, Duration.ofSeconds(10)),
                Arrays.asList(childSupplier, childSupplier, childSupplier, supervisor2));
        return supervisor;
    }
}
