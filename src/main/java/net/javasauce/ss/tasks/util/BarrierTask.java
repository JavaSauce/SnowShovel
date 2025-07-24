package net.javasauce.ss.tasks.util;

import net.javasauce.ss.util.task.Task;

import java.util.concurrent.ForkJoinPool;

/**
 * A task which does not execute anything. Purely used as a
 * barrier to require multiple tasks complete before continuing.
 * <p>
 * Created by covers1624 on 6/29/25.
 */
public class BarrierTask extends Task {

    public BarrierTask(String name) {
        super(name, ForkJoinPool.commonPool());
    }

    @Override
    protected void execute() throws Throwable { }
}
