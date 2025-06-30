package net.javasauce.ss.tasks.util;

import net.javasauce.ss.util.task.Task;

import java.util.concurrent.ForkJoinPool;

/**
 * A task which does not execute anything. Purely used as a
 * barrier to require multiple tasks complete before continuing.
 * <p>
 * Created by covers1624 on 6/29/25.
 */
// TODO This may not really be required anymore, we can probably get rid of it.
//      but we need the rest of the tasks to fall into place to know for sure.
public class BarrierTask extends Task {

    public BarrierTask(String name) {
        super(name, ForkJoinPool.commonPool());
    }

    @Override
    protected void execute() throws Throwable { }
}
