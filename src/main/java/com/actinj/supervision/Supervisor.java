package com.actinj.supervision;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public class Supervisor implements Supplier<ChildSpec>, Runnable {
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

    @Override
    public void run() {
        keepRunning.set(true);
        try {
            ConcurrentHashMap<String, ThreadConfig> newChildren = new ConcurrentHashMap<>();
            final Supervisor supervisor = this;
            for (final ChildSpec childSpec : childSpecList) {
                if (null == newChildren.putIfAbsent(childSpec.id(), new ThreadConfig(null, childSpec, null))) {
                    // If we did not already have a thread for this id
                    final AtomicReference<Throwable> causeOfFailure = new AtomicReference<>();
                    final Thread thread = Thread.ofVirtual().start(() -> {
                        try {
                            childSpec.start().run();
                        } catch (Throwable t) {
                            System.out.println("Thread failed: " + t.getMessage());
                            causeOfFailure.set(t);
                        } finally {
                            // Notify the supervisor to check its children now
                            System.out.println("Thread stopped");
                            synchronized (supervisor) {
                                supervisor.notifyAll();
                            }
                        }
                    });
                    newChildren.put(childSpec.id(), new ThreadConfig(thread, childSpec, causeOfFailure));
                    thread.setName(id + " - " + childSpec.id());
                }
            }
            children = newChildren;
            while (keepRunning.get()) {
                try {
                    synchronized (this) {
                        wait(Duration.ofSeconds(3).toMillis());
                    }
                    System.out.println("Checking children");
                } catch (InterruptedException e) {
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
