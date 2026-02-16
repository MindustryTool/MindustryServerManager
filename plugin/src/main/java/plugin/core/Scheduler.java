package plugin.core;

import plugin.annotations.Component;
import plugin.annotations.Destroy;
import plugin.annotations.MainThread;
import plugin.annotations.Schedule;

import java.lang.reflect.Method;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import arc.util.Log;
import arc.util.Time;
import arc.util.Timer;

@Component
public class Scheduler {

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        return scheduler.schedule(command, delay, unit);
    }

    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
        return scheduler.scheduleAtFixedRate(command, initialDelay, period, unit);
    }

    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
        return scheduler.scheduleWithFixedDelay(command, initialDelay, delay, unit);
    }

    public void process(Schedule schedule, Object instance, Method method) {
        boolean isMainThread = method.isAnnotationPresent(MainThread.class);
        Runnable task = createInvocationTask(instance, method, isMainThread);

        if (schedule.fixedRate() > -1) {
            scheduleFixedRate(schedule, method, task, isMainThread);
        } else if (schedule.fixedDelay() > -1) {
            scheduleFixedDelay(schedule, method, task, isMainThread);
        } else if (schedule.delay() > -1) {
            scheduleOneTime(schedule, method, task, isMainThread);
        } else {
            Log.warn("Invalid scheduler " + schedule);
        }
    }

    private void scheduleFixedRate(Schedule schedule, Method method, Runnable task, boolean isMainThread) {
        long expected = schedule.unit().toMillis(schedule.fixedRate());
        Runnable wrapped = createWrapper(task, method, expected, "fixed rate");

        if (isMainThread) {
            Timer.schedule(wrapped, schedule.unit().toSeconds(Math.max(0, schedule.delay())),
                    schedule.unit().toSeconds(schedule.fixedRate()));
        } else {
            scheduler.scheduleAtFixedRate(wrapped, Math.max(0, schedule.delay()), schedule.fixedRate(),
                    schedule.unit());
        }
        Log.info("[gray]Scheduled " + method + " with fixed rate " + schedule.fixedRate() + " " + schedule.unit());
    }

    private void scheduleFixedDelay(Schedule schedule, Method method, Runnable task, boolean isMainThread) {
        long expected = schedule.unit().toMillis(schedule.fixedDelay());
        Runnable wrapped = createWrapper(task, method, expected, "fixed delay");

        if (isMainThread) {
            Timer.schedule(new Timer.Task() {
                @Override
                public void run() {
                    wrapped.run();
                    Timer.schedule(this, schedule.unit().toSeconds(schedule.fixedDelay()));
                }
            }, schedule.unit().toSeconds(Math.max(0, schedule.delay())));
        } else {
            scheduler.scheduleWithFixedDelay(wrapped, Math.max(0, schedule.delay()), schedule.fixedDelay(),
                    schedule.unit());
        }
        Log.info("[gray]Scheduled " + method + " with fixed delay " + schedule.fixedDelay() + " " + schedule.unit());
    }

    private void scheduleOneTime(Schedule schedule, Method method, Runnable task, boolean isMainThread) {
        if (isMainThread) {
            Timer.schedule(task, schedule.unit().toSeconds(schedule.delay()));
        } else {
            scheduler.schedule(task, schedule.delay(), schedule.unit());
        }
        Log.info("[gray]Scheduled " + method + " with delay " + schedule.delay() + " " + schedule.unit());
    }

    private Runnable createWrapper(Runnable task, Method method, long expected, String type) {
        return () -> {
            long start = Time.millis();
            task.run();
            long elapsed = Time.millis() - start;
            if (elapsed > expected) {
                Log.warn("Task @" + method + " took " + elapsed + "ms, which is longer than the " + type + " "
                        + expected + "ms");
            }
        };
    }

    private Runnable createInvocationTask(Object instance, Method method, boolean isMainThread) {
        return () -> {
            try {
                long start = Time.millis();
                method.invoke(instance);
                long elapsed = Time.millis() - start;
                if (isMainThread && elapsed > 1000) {
                    Log.warn("Task @" + method + " took " + elapsed + "ms");
                }
            } catch (Exception e) {
                Log.err("Failed to invoke @" + method, e);
            }
        };
    }

    @Destroy
    public void shutdown() {
        scheduler.shutdownNow();
    }
}
