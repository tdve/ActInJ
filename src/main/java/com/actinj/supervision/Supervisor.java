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

    public record childCount(int specs, int active, int supervisors, int workers) {
    }

    private enum ActionType {
        RESTART, ADD, DELETE
    }

    private record PendingAction(ActionType type, ChildSpec childSpec) {
    }

    private final String id;
    private final SupervisorFlags flags;
    private List<ThreadConfig> children = Collections.emptyList();
    private final Set<String> childNames = ConcurrentHashMap.newKeySet();
    private List<ChildSpec> savedState;
    private final AtomicBoolean keepRunning = new AtomicBoolean(true);
    private final ConcurrentLinkedQueue<PendingAction> pendingActions = new ConcurrentLinkedQueue<>();

    public Supervisor(String id, SupervisorFlags flags, List<Supplier<ChildSpec>> children) {
        this.id = id;
        this.flags = flags;
        this.savedState = children.stream().map(Supplier::get).toList();
    }

    public childCount countChildren() {
        final List<ChildSpec> specs = savedState;
        // This can never be longer than specs.size(), so it should fit in an int
        final int supervisors = (int) specs.stream().map(ChildSpec::start).filter(Supervisor.class::isInstance).count();
        final int active = (int) children.stream().map(ThreadConfig::thread).filter(Objects::nonNull)
                .filter(Thread::isAlive).count();
        return new childCount(specs.size(), active, supervisors, specs.size() - supervisors);
    }

    public boolean deleteChild(final String id) {
        final Optional<ThreadConfig> threadConfig = children.stream()
                .filter(conf -> conf.spec().id().equals(id) && (null == conf.thread() || !conf.thread().isAlive()))
                .findAny();
        if (threadConfig.isEmpty())
            return false;
        pendingActions.add(new PendingAction(ActionType.DELETE, threadConfig.get().spec()));
        return true;
    }

    public Optional<ChildSpec> getChildspec(final String id) {
        return savedState.stream().filter(spec -> spec.id().equals(id)).findAny();
    }

    public boolean restartChild(final String id) {
        final Optional<ThreadConfig> threadConfig = children.stream()
                .filter(conf -> conf.spec().id().equals(id) && null != conf.thread() && !conf.thread().isAlive())
                .findAny();
        if (threadConfig.isEmpty())
            return false; // Not found or still running
        pendingActions.add(new PendingAction(ActionType.RESTART, threadConfig.get().spec));
        return true;
    }

    public boolean startChild(final ChildSpec childSpec) {
        if (childNames.add(childSpec.id())) {
            pendingActions.add(new PendingAction(ActionType.ADD, childSpec));
            return true;
        }
        return false;
    }

    public boolean terminateChild(final String id) {
        final Optional<ThreadConfig> threadConfig = children.stream()
                .filter(conf -> conf.spec().id().equals(id) && null != conf.thread() && conf.thread().isAlive())
                .findAny();
        if (threadConfig.isEmpty())
            return false;
        threadConfig.get().thread().interrupt();
        return true;
    }

    public void stop() {
        keepRunning.set(false);
    }

    @Override
    public ChildSpec get() {
        return new ChildSpec(id, this, Restart.PERMANENT, Duration.ofSeconds(30));
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
                // Now, we want to run the pending actions
                PendingAction action = pendingActions.poll();
                while (null != action) {
                    children = processAction(action.type(), action.childSpec());
                    action = pendingActions.poll();
                }
                // We made it this far. Let's update the state, so we have it to recover from in case a restart happens
                savedState = children.stream().map(ThreadConfig::spec).toList();
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

    private List<ThreadConfig> processAction(final ActionType type, final ChildSpec childSpec) {
        switch (type) {
        case RESTART -> {
            return children.stream()
                    .map(threadConfig -> childSpec.id().equals(threadConfig.spec().id())
                            && (null == threadConfig.thread() || !threadConfig.thread().isAlive())
                                    ? startThread(childSpec) : threadConfig)
                    .toList();
        }
        case ADD -> {
            if (children.stream().noneMatch(tc -> tc.spec().id().equals(childSpec.id()))) {
                childNames.add(childSpec.id()); // Make sure it's listed
                return Stream.concat(children.stream(), Stream.of(startThread(childSpec))).toList();
            }
            return children; // We do nothing
        }
        case DELETE -> {
            final List<ThreadConfig> result = children.stream()
                    .filter(threadConfig -> !childSpec.id().equals(threadConfig.spec().id())
                            || (null != threadConfig.thread() && threadConfig.thread().isAlive()))
                    .toList();
            if (result.stream().noneMatch(tc -> tc.spec().id().equals(childSpec.id())))
                childNames.remove(childSpec.id());
            return result;
        }
        }
        return children;
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
        // TODO: we should not do this in parallel. We should stop them one by one, and give them time to finish
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
