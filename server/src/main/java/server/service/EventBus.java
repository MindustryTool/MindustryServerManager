package server.service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import arc.util.Log;
import events.BaseEvent;

public class EventBus {

    private final List<Consumer<BaseEvent>> consumers = new ArrayList<>();

    public Runnable on(Consumer<BaseEvent> consumer) {
        consumers.add(consumer);

        return () -> consumers.remove(consumer);
    }

    public <T extends BaseEvent> T emit(T event) {
        for (var consumer : consumers) {
            try {
                consumer.accept(event);
            } catch (Exception e) {
                Log.err("Error while handling event: " + event.getClass().getSimpleName(), e);
            }
        }

        return event;
    }
}
