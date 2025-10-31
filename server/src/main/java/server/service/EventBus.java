package server.service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.springframework.stereotype.Service;

import arc.util.Log;
import events.BaseEvent;
import events.ServerEvents.LogEvent;

@Service
public class EventBus {

    private final List<Consumer<BaseEvent>> consumers = new ArrayList<>(List.of(this::eventLogger));

    public Runnable on(Consumer<BaseEvent> consumer) {
        consumers.add(consumer);

        return () -> consumers.remove(consumer);
    }

    public <T extends BaseEvent> T fire(T event) {
        for (var consumer : consumers) {
            try {
                consumer.accept(event);
            } catch (Exception e) {
                Log.err("Error while handling event: " + event.getClass().getSimpleName(), e);
            }
        }

        return event;
    }

    private void eventLogger(BaseEvent event) {
        if (event instanceof LogEvent) {
            return;
        }

        Log.info("[" + event.getServerId() + "] Event: " + event.getClass().getSimpleName());
    }
}
