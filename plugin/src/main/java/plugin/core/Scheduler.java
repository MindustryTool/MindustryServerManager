package plugin.core;

import plugin.annotations.Component;
import plugin.annotations.Destroy;
import plugin.annotations.Schedule;

import java.lang.reflect.Method;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import arc.util.Log;

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

        Runnable runnable = () -> {
            try {
                method.invoke(instance);
            } catch (Exception e) {
                Log.err("Failed to invoke @" + method, e);
            }
        };

        if (schedule.fixedRate() > -1) {
            scheduler.scheduleAtFixedRate(runnable, Math.max(0, schedule.delay()), schedule.fixedRate(),
                    schedule.unit());
            Log.info("[gray]Scheduled " + method + " with fixed rate " + schedule.fixedRate() + " " + schedule.unit());
        } else if (schedule.fixedDelay() > -1) {
            scheduler.scheduleWithFixedDelay(runnable, Math.max(0, schedule.delay()), schedule.fixedDelay(),
                    schedule.unit());
            Log.info("[gray]Scheduled " + method + " with fixed delay " + schedule.fixedDelay() + " " + schedule.unit());
        } else if (schedule.delay() > -1) {
            scheduler.schedule(runnable, schedule.delay(), schedule.unit());
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
