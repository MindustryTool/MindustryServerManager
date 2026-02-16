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
        var isMainThread = method.isAnnotationPresent(MainThread.class);

        Runnable runnable = () -> {
            try {
                long startedAt = Time.millis();
                method.invoke(instance);
                long elapsed = Time.millis() - startedAt;
                if (isMainThread && elapsed > 1000) {
                    Log.warn("Task @" + method + " took " + elapsed + "ms");
                }
            } catch (Exception e) {
                Log.err("Failed to invoke @" + method, e);
            }
        };

        if (schedule.fixedRate() > -1) {
            Runnable task = () -> {
                long startedAt = Time.millis();
                runnable.run();
                long elapsed = Time.millis() - startedAt;
                long expected = schedule.unit().toMillis(schedule.fixedRate());
                if (elapsed > expected) {
                    Log.warn("Task @" + method + " took " + elapsed + "ms, which is longer than the fixed rate "
                            + expected + "ms");
                }
            };

            if (isMainThread) {
                Timer.schedule(task, schedule.unit().toSeconds(Math.max(0, schedule.delay())),
                        schedule.unit().toSeconds(schedule.fixedRate()));
            } else {
                scheduler.scheduleAtFixedRate(task, Math.max(0, schedule.delay()), schedule.fixedRate(),
                        schedule.unit());
            }
            Log.info("[gray]Scheduled " + method + " with fixed rate " + schedule.fixedRate() + " " + schedule.unit());
        } else if (schedule.fixedDelay() > -1) {
            Runnable task = () -> {
                long startedAt = Time.millis();
                runnable.run();
                long elapsed = Time.millis() - startedAt;
                long expected = schedule.unit().toMillis(schedule.fixedDelay());

                if (elapsed > expected) {
                    Log.warn("Task @" + method + " took " + elapsed + "ms, which is longer than the fixed delay "
                            + expected + "ms");
                }
            };
            if (isMainThread) {
                Timer.schedule(new Timer.Task() {
                    @Override
                    public void run() {
                        runnable.run();
                        Timer.schedule(this, schedule.unit().toSeconds(schedule.fixedDelay()));
                    }
                }, schedule.unit().toSeconds(Math.max(0, schedule.delay())));
            } else {
                scheduler.scheduleWithFixedDelay(task, Math.max(0, schedule.delay()), schedule.fixedDelay(),
                        schedule.unit());
            }
            Log.info(
                    "[gray]Scheduled " + method + " with fixed delay " + schedule.fixedDelay() + " " + schedule.unit());
        } else if (schedule.delay() > -1) {
            if (isMainThread) {
                Timer.schedule(runnable, schedule.unit().toSeconds(schedule.delay()));
            } else {
                scheduler.schedule(runnable, schedule.delay(), schedule.unit());
            }
            Log.info("[gray]Scheduled " + method + " with delay " + schedule.delay() + " " + schedule.unit());
        } else {
            Log.warn("Invalid scheduler " + schedule);
        }
    }

    @Destroy
    public void shutdown() {
        scheduler.shutdownNow();
    }
}
