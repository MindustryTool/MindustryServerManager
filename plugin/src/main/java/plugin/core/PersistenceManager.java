package plugin.core;

import arc.files.Fi;
import arc.struct.Seq;
import arc.util.Log;
import dto.Pair;
import mindustry.Vars;
import plugin.annotations.Destroy;
import plugin.annotations.Persistence;
import plugin.utils.JsonUtils;

import java.lang.reflect.Field;

public class PersistenceManager {
    public final Seq<Pair<Object, Pair<Field, Persistence>>> persistenceObjects = new Seq<>();

    public void load(Field field, Persistence persistence, Object instance) {
        persistenceObjects.add(new Pair<>(instance, new Pair<>(field, persistence)));

        String path = persistence.value();
        Fi file = Vars.dataDirectory.child(path);

        if (file.exists()) {
            try {
                String json = file.readString();
                Object value = JsonUtils.readJson(json, field.getGenericType());
                field.set(instance, value);
                Log.info("[gray]Loaded persistence for field @ at @", field.getName(), path);
            } catch (Exception e) {
                Log.err("Failed to load persistence for field @ at @", field.getName(), path);
                Log.err(e);
            }
        }
    }

    @Destroy
    public void destroy() {
        for (var pair : persistenceObjects) {
            var instance = pair.first;
            var field = pair.second.first;
            var persistence = pair.second.second;

            try {
                String path = persistence.value();
                Fi file = Vars.dataDirectory.child(path);
                Object value = field.get(instance);

                if (value != null) {
                    file.writeString(JsonUtils.toJsonString(value));
                    Log.info("[gray]Saved persistence for field @ at @", field.getName(), path);
                }
            } catch (Exception e) {
                Log.err("Failed to destroy persistence for field @ in @", field.getName(),
                        instance.getClass().getSimpleName());
                Log.err(e);
            }
        }
    }
}
