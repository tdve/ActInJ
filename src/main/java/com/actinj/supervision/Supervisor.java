package com.actinj.supervision;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public class Supervisor implements Supplier<ChildSpec>, Runnable {
    private static final Logger logger = LoggerFactory.getLogger(Supervisor.class);

    private record ThreadConfig(Thread thread, ChildSpec spec, AtomicReference<Throwable> failure) {
    }

    private final String id;
    private final SupervisorFlags flags;
    // TODO: not a HashMap. The order is important
    private ConcurrentHashMap<String, ThreadConfig> children = new ConcurrentHashMap<>();
    private List<ChildSpec> childSpecList;
    private final AtomicBoolean keepRunning = new AtomicBoolean(true);

    public Supervisor(String id, SupervisorFlags flags, List<ChildSpec> children) {
        this.id = id;
        this.flags = flags;
        this.childSpecList = children;
    }

    public void stop() {
        keepRunning.set(false);
    }

    private ThreadConfig startThread(final ChildSpec childSpec) {
        final Supervisor supervisor = this;
        final AtomicReference<Throwable> causeOfFailure = new AtomicReference<>();
        final String threadName = id + "/" + childSpec.id();
        final Thread thread = Thread.ofVirtual().name(threadName).start(() -> {
            try {
                childSpec.start().run();
            } catch (Throwable t) {
                // causeOfFailure helps us distinguish between crashed threads and normally stopped threads
                // TODO: I think we do want to catch Errors here, so we know that they ocured, but maybe
                // we should rethrow them? Or doesn't it matter as this thread is about to stop anyway?
                logger.warn("Thread {} failed", threadName, t);
                causeOfFailure.set(t);
            } finally {
                logger.info("Thread {} stopped", threadName);
                // Notify the supervisor to check its children now
                synchronized (supervisor) {
                    supervisor.notifyAll();
                }
            }
        });
        return new ThreadConfig(thread, childSpec, causeOfFailure);
    }

    @Override
    public void run() {
        // There might be a parent supervisor who restarted us, so set keepRunning (back) to true
        keepRunning.set(true);
        try {
            ConcurrentHashMap<String, ThreadConfig> newChildren = new ConcurrentHashMap<>();
            for (final ChildSpec childSpec : childSpecList) {
                if (null == newChildren.putIfAbsent(childSpec.id(), new ThreadConfig(null, childSpec, null))) {
                    // If we did not already have a thread for this id
                    newChildren.put(childSpec.id(), startThread(childSpec));
                }
            }
            children = newChildren;
            while (keepRunning.get()) {
                try {
                    synchronized (this) {
                        wait(Duration.ofSeconds(3).toMillis());
                    }
                    logger.debug("Supervisor {} checking children", id);
                } catch (InterruptedException e) {
                    logger.debug("Supervisor {} stopped by InterruptedException", id);
                    Thread.currentThread().interrupt();
                    return; // We need to stop
                }
            }
        } finally {
            // Stop all child threads should they still be running
            for (final ThreadConfig child : children.values()) {
                final Thread childThread = child.thread();
                if (null != childThread) {
                    // An interrupt does not seem ideal. Preferably, we would really want to kill this. But that does
                    // not seem to be possible
                    childThread.interrupt();
                }
            }

        }
    }

    @Override
    public ChildSpec get() {
        return new ChildSpec(id, this, Restart.TRANSIENT, Duration.ofHours(1));
    }

}
