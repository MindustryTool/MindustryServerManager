package plugin.utils;

import arc.Core;
import arc.files.Fi;
import arc.util.Log;
import plugin.annotations.Component;
import plugin.annotations.Init;

@Component
public class TranslationLoader {
    @Init
    private void init() {
        Fi[] files = Core.files.internal("i18n").list();

        if (files == null) {
            Log.warn("TranslationLoader: no i18n directory found on classpath");
            return;
        }

        for (Fi file : files) {
            if (!file.name().endsWith(".json")) {
                continue;
            }

            String language = file.name().substring(0, file.name().length() - ".json".length());
            String content = file.readString();

            Tr.catalog().load(language, content, message -> Log.warn("TranslationLoader: @", message));
        }
    }
}