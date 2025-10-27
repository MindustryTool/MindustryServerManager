package server.service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.springframework.stereotype.Service;

import arc.util.Log;
import events.BaseEvent;

@Service
public class EventBus {

    private final List<Consumer<BaseEvent>> consumers = new ArrayList<>(List.of(event -> Log.info("Event: {}", event)));

    public Runnable on(Consumer<BaseEvent> consumer) {
        consumers.add(consumer);

        return () -> consumers.remove(consumer);
    }

    public <T extends BaseEvent> T fire(T event) {
        for (var consumer : consumers) {
            consumer.accept(event);
        }

        return event;
    }
}
