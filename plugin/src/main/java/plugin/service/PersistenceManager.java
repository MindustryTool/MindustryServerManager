package plugin.service;

import arc.files.Fi;
import arc.util.Log;
import mindustry.Vars;
import plugin.annotations.Persistence;
import plugin.utils.JsonUtils;

import java.lang.reflect.Field;

public class PersistenceManager {

    public void load(Object instance) {
        Class<?> clazz = instance.getClass();
        for (Field field : clazz.getDeclaredFields()) {
            if (field.isAnnotationPresent(Persistence.class)) {
                try {
                    field.setAccessible(true);
                    Persistence persistence = field.getAnnotation(Persistence.class);
                    String path = persistence.value();
                    Fi file = Vars.dataDirectory.child(path);

                    if (file.exists()) {
                        String json = file.readString();
                        Object value = JsonUtils.readJson(json, field.getGenericType());
                        field.set(instance, value);
                        Log.info("Loaded persistence for field @ in @ from @", field.getName(), clazz.getSimpleName(),
                                path);
                    }
                } catch (Exception e) {
                    Log.err("Failed to load persistence for field @ in @", field.getName(), clazz.getName(), e);
                }
            }
        }
    }

    public void save(Object instance) {
        Class<?> clazz = instance.getClass();
        for (Field field : clazz.getDeclaredFields()) {
            if (field.isAnnotationPresent(Persistence.class)) {
                try {
                    field.setAccessible(true);
                    Persistence persistence = field.getAnnotation(Persistence.class);
                    String path = persistence.value();
                    Fi file = Vars.dataDirectory.child(path);

                    Object value = field.get(instance);
                    if (value != null) {
                        if (!file.exists()) {
                            file.parent().mkdirs();
                        }
                        String json = JsonUtils.toJsonString(value);
                        file.writeString(json);
                        Log.info("Saved persistence for field @ in @ to @", field.getName(),
                                clazz.getSimpleName(), path);
                    }
                } catch (Exception e) {
                    Log.err("Failed to save persistence for field @ in @", field.getName(),
                            clazz.getName(), e);
                }
            }
        }
    }
}
