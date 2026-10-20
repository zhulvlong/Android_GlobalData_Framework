package com.oem.globaldata.client;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;

/** 即使 App 提供多线程 Executor，也按订阅产生的顺序逐个执行回调。 */
final class SerialExecutor implements Executor {
    /** 真正执行任务的 App Executor；本类只负责排序。 */
    private final Executor delegate;
    /** 等待中的回调队列，受本实例同步锁保护。 */
    private final Queue<Runnable> tasks = new ArrayDeque<>();
    /** 当前交给 delegate 的任务；为 null 才能派发队首。 */
    private Runnable active;
    /** 每个数据或事件订阅分别拥有一个排序器，互不阻塞。 */
    SerialExecutor(Executor delegate) { this.delegate = delegate; }
    /**
     * 将任务包装成“完成后调度下一项”，避免并行 Executor 打乱回调顺序。
     * @param command 订阅者的回调任务
     */
    @Override public synchronized void execute(Runnable command) {
        // finally 保证业务回调抛错时仍释放下一个回调的执行机会。
        tasks.offer(() -> { try { command.run(); } finally { scheduleNext(); } });
        if (active == null) scheduleNext();
    }
    /** 只派发一个队首任务；后续任务在它结束时再触发。 */
    private synchronized void scheduleNext() {
        active = tasks.poll();
        if (active != null) delegate.execute(active);
    }
}
