package io.mrarm.irc.util;

import android.os.AsyncTask;
import androidx.annotation.NonNull;

import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A utility class that executes the tasks in order, for use with a ThreadPoolExecutor.
 * Also implements ExecutorService for proper lifecycle management.
 */
public class PoolSerialExecutor implements ExecutorService {

    private final ExecutorService mParentExecutor;
    private final Queue<Runnable> mQueue = new LinkedList<>();
    private final AtomicBoolean mQueuedMainTask = new AtomicBoolean(false);
    private final AtomicBoolean mShutdown = new AtomicBoolean(false);

    public PoolSerialExecutor(ExecutorService parentExecutor) {
        mParentExecutor = parentExecutor;
    }

    public PoolSerialExecutor() {
        this((ExecutorService) AsyncTask.THREAD_POOL_EXECUTOR);
    }

    @Override
    public void execute(@NonNull Runnable runnable) {
        if (mShutdown.get()) {
            throw new IllegalStateException("Executor has been shutdown");
        }
        synchronized (this) {
            mQueue.add(runnable);
            if (mQueuedMainTask.compareAndSet(false, true)) {
                mParentExecutor.execute(mRunTasksRunnable);
            }
        }
    }

    private final Runnable mRunTasksRunnable = () -> {
        while (true) {
            Runnable runnable;
            synchronized (this) {
                if (mQueue.isEmpty()) {
                    mQueuedMainTask.set(false);
                    return;
                }
                runnable = mQueue.remove();
            }
            if (!mShutdown.get()) {
                runnable.run();
            }
        }
    };

    @Override
    public void shutdown() {
        mShutdown.set(true);
        // Don't shutdown parent executor as it's shared (AsyncTask.THREAD_POOL_EXECUTOR)
    }

    @Override
    public List<Runnable> shutdownNow() {
        mShutdown.set(true);
        List<Runnable> remaining;
        synchronized (this) {
            remaining = new LinkedList<>(mQueue);
            mQueue.clear();
        }
        return remaining;
    }

    @Override
    public boolean isShutdown() {
        return mShutdown.get();
    }

    @Override
    public boolean isTerminated() {
        return mShutdown.get() && mQueue.isEmpty() && !mQueuedMainTask.get();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (true) {
            if (isTerminated()) {
                return true;
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return false;
            }
            Thread.sleep(Math.min(100, unit.toMillis(remaining)));
        }
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        return mParentExecutor.submit(task);
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        return mParentExecutor.submit(task, result);
    }

    @Override
    public Future<?> submit(Runnable task) {
        return mParentExecutor.submit(task);
    }

    @Override
    public <T> List<Future<T>> invokeAll(
            java.util.Collection<? extends Callable<T>> tasks) throws InterruptedException {
        return mParentExecutor.invokeAll(tasks);
    }

    @Override
    public <T> List<Future<T>> invokeAll(
            java.util.Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException {
        return mParentExecutor.invokeAll(tasks, timeout, unit);
    }

    @Override
    public <T> T invokeAny(java.util.Collection<? extends Callable<T>> tasks)
            throws InterruptedException, java.util.concurrent.ExecutionException {
        return mParentExecutor.invokeAny(tasks);
    }

    @Override
    public <T> T invokeAny(java.util.Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException, java.util.concurrent.ExecutionException, java.util.concurrent.TimeoutException {
        return mParentExecutor.invokeAny(tasks, timeout, unit);
    }
}