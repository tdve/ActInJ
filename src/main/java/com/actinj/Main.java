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

public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws InterruptedException {
        final Runnable runnable = () -> {
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
        final ChildSpec childSpec = new ChildSpec("First", runnable, Restart.TRANSIENT, Duration.ofSeconds(1));
        final ChildSpec childSpec2 = new ChildSpec("Second", runnable, Restart.TRANSIENT, Duration.ofSeconds(1));
        final ChildSpec childSpec3 = new ChildSpec("Third", runnable, Restart.TRANSIENT, Duration.ofSeconds(1));
        final ChildSpec childSpec4 = new ChildSpec("Forth", runnable, Restart.TRANSIENT, Duration.ofSeconds(1));
        final ChildSpec childSpec5 = new ChildSpec("Fifth", runnable, Restart.TRANSIENT, Duration.ofSeconds(1));
        final Supervisor supervisor2 = new Supervisor("sup2",
                new SupervisorFlags(Strategy.ONE_FOR_ONE, 5, Duration.ofSeconds(1)),
                Arrays.asList(childSpec4, childSpec5));
        final Supervisor supervisor = new Supervisor("sup1",
                new SupervisorFlags(Strategy.ONE_FOR_ONE, 5, Duration.ofSeconds(1)),
                Arrays.asList(childSpec, childSpec2, childSpec3, supervisor2.get()));
        Thread.ofVirtual().start(supervisor);
        Thread.sleep(Duration.ofSeconds(10));
        supervisor.stop();
        Thread.sleep(Duration.ofSeconds(10));
    }
}
