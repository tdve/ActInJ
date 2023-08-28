package com.actinj.supervision;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;

public class Supervisor implements Supplier<ChildSpec>, Runnable {
    private static final Logger logger = LoggerFactory.getLogger(Supervisor.class);

    private record ThreadConfig(Thread thread, ChildSpec spec, AtomicReference<Throwable> failure) {
    }

    private enum ActionType {
        RESTART
    }

    private record PendingAction(ActionType type, ChildSpec childSpec) {
    }

    private final String id;
    private final SupervisorFlags flags;
    private List<ThreadConfig> children = Collections.emptyList();
    private Set<String> childNames = ConcurrentHashMap.newKeySet();
    private List<ChildSpec> savedState;
    private final AtomicBoolean keepRunning = new AtomicBoolean(true);
    private final ConcurrentLinkedQueue<PendingAction> pendingActions = new ConcurrentLinkedQueue<>();

    public Supervisor(String id, SupervisorFlags flags, List<Supplier<ChildSpec>> children) {
        this.id = id;
        this.flags = flags;
        this.savedState = children.stream().map(Supplier::get).toList();
    }

    public void stop() {
        keepRunning.set(false);
    }

    @Override
    public ChildSpec get() {
        return new ChildSpec(id, this, Restart.PERMANENT, Duration.ofSeconds(30));
    }

    private ThreadConfig startThread(final ChildSpec childSpec) {
        final Supervisor supervisor = this;
        final AtomicReference<Throwable> causeOfFailure = new AtomicReference<>();
        final String threadName = id + "/" + childSpec.id();
        // Maybe we want to us some configurable ThreadFactory here?
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
        AtomicReference<List<Instant>> restarts = new AtomicReference<>(Collections.emptyList());
        try {
            // We just started. We might be recovering from a crash
            childNames.clear();
            children = savedState.stream().filter(childSpec -> childNames.add(childSpec.id())).map(this::startThread)
                    .toList();
            while (keepRunning.get()) {
                // The first thing we do in any loop, is to save the previous state, should we need to
                // recover from it after a crash in the future
                savedState = children.stream().map(ThreadConfig::spec).toList();
                // Now, we want to run the pending actions
                PendingAction action = pendingActions.poll();
                while (null != action) {
                    if (action.type() == ActionType.RESTART) {
                        final ChildSpec childSpec = action.childSpec();
                        children = children.stream().map(threadConfig -> childSpec.id().equals(threadConfig.spec().id())
                                ? startThread(childSpec) : threadConfig).toList();
                    }
                    action = pendingActions.poll();
                }
                try {
                    synchronized (this) {
                        // 3s for testing. We'd want this to be faster later
                        wait(Duration.ofSeconds(3).toMillis());
                    }
                    logger.debug("Supervisor {} checking children", id);
                    checkChildren(restarts);
                } catch (InterruptedException e) {
                    logger.debug("Supervisor {} stopped by InterruptedException", id);
                    Thread.currentThread().interrupt();
                    return; // We need to stop
                }
            }
        } finally {
            // Stop all child threads should they still be running
            interruptAndWaitToFinish(children);
        }
    }

    private void checkChildren(AtomicReference<List<Instant>> restarts) {
        try {
            for (final ThreadConfig child : children) {
                if (!child.thread().isAlive()) {
                    switch (child.spec().restart()) {
                    case Restart.TRANSIENT -> transientChildStopped(child, restarts);
                    case Restart.TEMPORARY -> logger.info("Temporary thread {} stopped", child.spec.id());
                    case Restart.PERMANENT -> permanentChildStopped(child, restarts);
                    }
                }
            }
        } catch (StopChildrenCheckLoop e) {
            // We're done looping. A stopped thread caused all remaining threads to restart, so no more need to check
            // them
        }
    }

    private void transientChildStopped(final ThreadConfig child, final AtomicReference<List<Instant>> restarts)
            throws StopChildrenCheckLoop {
        final Throwable failure = child.failure.get();
        if (null != failure) {
            logger.warn("Transient thread {} crashed", child.spec.id(), failure);
            restarts.set(updateRestarts(restarts.get()));
            attemptRestart(child.spec());
        } else {
            logger.info("Transient thread {} stopped normally", child.spec.id());
        }
    }

    private void permanentChildStopped(final ThreadConfig child, final AtomicReference<List<Instant>> restarts)
            throws StopChildrenCheckLoop {
        final Throwable failure = child.failure.get();
        if (null != failure) {
            logger.warn("Permanent thread {} crashed", child.spec.id(), failure);
        } else {
            logger.warn("Permanent thread {} stopped", child.spec.id());
        }
        restarts.set(updateRestarts(restarts.get()));
        attemptRestart(child.spec());
    }

    private List<Instant> updateRestarts(final List<Instant> restarts) {
        final Instant now = Instant.now();
        final Instant begin = now.minus(flags.period());
        final List<Instant> result = Stream.concat(Stream.of(Instant.now()), restarts.stream()).filter(begin::isBefore)
                .toList();
        if (result.size() > flags.intensity()) {
            keepRunning.set(false);
            logger.warn("Allowed number of restarts exceeded. Supervisor will stop");
        }
        return result;
    }

    private void attemptRestart(final ChildSpec childSpec) throws StopChildrenCheckLoop {
        switch (flags.strategy()) {
        case ONE_FOR_ONE -> pendingActions.add(new PendingAction(ActionType.RESTART, childSpec));
        case ONE_FOR_ALL -> {
            children.forEach(
                    threadConfig -> pendingActions.add(new PendingAction(ActionType.RESTART, threadConfig.spec())));
            interruptAndWaitToFinish(children);
            throw new StopChildrenCheckLoop();
        }
        case REST_FOR_ONE -> {
            final AtomicBoolean seen = new AtomicBoolean(false);
            final List<ThreadConfig> toStop = children.stream().filter(threadConfig -> {
                seen.compareAndSet(false, threadConfig.spec().id().equals(childSpec.id()));
                return seen.get();
            }).toList();
            toStop.forEach(
                    threadConfig -> pendingActions.add(new PendingAction(ActionType.RESTART, threadConfig.spec())));
            interruptAndWaitToFinish(toStop);
            throw new StopChildrenCheckLoop();
        }
        }
    }

    private void interruptAndWaitToFinish(List<ThreadConfig> threadConfigs) {
        threadConfigs.stream().map(ThreadConfig::thread).filter(Objects::nonNull).forEach(Thread::interrupt);
        final Instant timeoutStart = Instant.now();
        // We nicely ask the threads to stop, by sending them an interrupt. Now we'll give them some time to comply, so
        // they can clean up their resources, before we restart them. Ideally, if they don't stop within
        // ChildSpec::shutdown, we would kill them. But there is no API to do this for Virtual Threads...
        boolean wasInterrupted = false;
        do {
            threadConfigs = threadConfigs.stream().filter(tc -> null != tc.thread()).filter(tc -> tc.thread().isAlive())
                    .filter(tc -> {
                        Instant expire = timeoutStart.plus(tc.spec().shutdown());
                        if (expire.isBefore(Instant.now())) {
                            logger.warn("Thread {} did not end within configured time limit of {}", tc.spec().id(),
                                    tc.spec().shutdown());
                            return false;
                        }
                        return true;
                    }).toList();
            // We'll wait for a random thread, and then again filter out all stopped ones
            Optional<ThreadConfig> toWaitFor = threadConfigs.stream().findAny();
            if (toWaitFor.isPresent()) {
                long expire = timeoutStart.plus(toWaitFor.get().spec().shutdown()).toEpochMilli();
                long toWait = expire - System.currentTimeMillis();
                if (toWait > 0) {
                    try {
                        toWaitFor.get().thread().join(toWait);
                    } catch (InterruptedException e) {
                        // We'll ignore this interrupt for now. If this Supervisor will end up getting restarted, it is
                        // important that we give all child threads the time to clean up their resources. Otherwise, the
                        // new threads after the restart, might fail because the old thread is still holding on to
                        // resources
                        logger.info("Ignoring interrupt while waiting for children to stop");
                        wasInterrupted = true;
                    }
                }
            }
        } while (!threadConfigs.isEmpty());
        if (wasInterrupted) // Now we are done waiting for children, and we can set the interrupt status back
            Thread.currentThread().interrupt();
    }

    private class StopChildrenCheckLoop extends Exception {
    }

}
