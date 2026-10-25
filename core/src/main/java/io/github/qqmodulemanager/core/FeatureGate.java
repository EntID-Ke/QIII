package io.github.qqmodulemanager.core;

import java.util.concurrent.locks.ReentrantReadWriteLock;

/** A host invocation keeps one owner until all its before/after callbacks finish. */
public final class FeatureGate {
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
    private final ThreadLocal<String> invocationOwner = new ThreadLocal<>();
    private volatile String owner = "";

    public String owner() {
        String snapshot = invocationOwner.get();
        return snapshot == null ? owner : snapshot;
    }
    public void switchTo(String next) {
        lock.writeLock().lock();
        try { owner = next; } finally { lock.writeLock().unlock(); }
    }
    public Object enter() {
        lock.readLock().lock();
        String previous = invocationOwner.get();
        invocationOwner.set(owner);
        return previous;
    }
    public void leave(Object previous) {
        if (previous == null) invocationOwner.remove(); else invocationOwner.set((String) previous);
        lock.readLock().unlock();
    }
}
