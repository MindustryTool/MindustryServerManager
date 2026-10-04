package plugin.chat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import arc.Core;
import arc.Events;
import arc.util.Log;
import arc.util.Strings;
import lombok.RequiredArgsConstructor;
import mindustry.Vars;
import mindustry.game.EventType.PlayerChatEvent;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import plugin.annotations.Component;
import plugin.annotations.Init;
import plugin.utils.Utils;

@Component
@RequiredArgsConstructor
public class ChatTranslation {

    private final TranslationService translationService;

    @Init
    private void init() {
        Vars.netServer.admins.addChatFilter((Player player, String message) -> {
            if (message == null) {
                return null;
            }

            // Bypass commands starting with /
            if (message.startsWith("/")) {
                return message;
            }

            // Snapshot online players and their locales on the main thread
            List<Player> targetPlayers = new ArrayList<>();
            Set<String> neededLangs = new HashSet<>();
            Groups.player.forEach(p -> {
                targetPlayers.add(p);
                String lang = Utils.parseLocale(p.locale()).getLanguage();
                if (!lang.isBlank()) {
                    neededLangs.add(lang);
                }
            });

            // Log chat and fire PlayerChatEvent for other event listeners (e.g. Discord bridges)
            Log.info("<Chat> @: @", Strings.stripColors(player.name), Strings.stripColors(message));
            Events.fire(new PlayerChatEvent(player, message));

            // Execute translation asynchronously to prevent blocking the game thread
            CompletableFuture.runAsync(() -> handleAsyncTranslation(player, message, targetPlayers, neededLangs));

            // Return null to suppress default synchronous broadcast
            return null;
        });
    }

    private void handleAsyncTranslation(Player sender, String message, List<Player> targetPlayers, Set<String> neededLangs) {
        try {
            String cleanText = Strings.stripColors(message).trim();
            Map<String, TranslationResult> translations = new HashMap<>();

            if (!cleanText.isEmpty()) {
                for (String lang : neededLangs) {
                    try {
                        TranslationResult res = translationService.translate(cleanText, lang);
                        if (res != null) {
                            translations.put(lang, res);
                        }
                    } catch (Exception e) {
                        Log.warn("Failed translating for lang '@': @", lang, e.getMessage());
                    }
                }
            }

            // Dispatch formatted messages to players on the main thread
            Core.app.post(() -> {
                for (Player recipient : targetPlayers) {
                    if (!recipient.isAdded() || recipient.con == null) {
                        continue;
                    }

                    String lang = Utils.parseLocale(recipient.locale()).getLanguage();
                    TranslationResult res = translations.get(lang);

                    String formatted = formatMessage(message, cleanText, lang, res);
                    recipient.sendMessage(formatted, sender, Strings.stripColors(formatted));
                }
            });
        } catch (Exception e) {
            Log.err("Error in chat translation worker", e);
            // Fallback: send original message directly on main thread
            Core.app.post(() -> {
                for (Player recipient : targetPlayers) {
                    if (recipient.isAdded() && recipient.con != null) {
                        recipient.sendMessage(message, sender, Strings.stripColors(message));
                    }
                }
            });
        }
    }

    public static String formatMessage(String originalMessage, String cleanText, String recipientLang, TranslationResult result) {
        if (result == null || result.translatedText() == null || result.translatedText().isBlank()) {
            return originalMessage;
        }

        // If detected source language is already the recipient's language, no translation needed
        if (result.sourceLanguage() != null && result.sourceLanguage().equalsIgnoreCase(recipientLang)) {
            return originalMessage;
        }

        // If translated text is identical to cleaned original, no translation needed
        if (result.translatedText().equalsIgnoreCase(cleanText)) {
            return originalMessage;
        }

        // Format as: <original> ([#00ff00]<translated>)
        // In Mindustry, '([' renders as a literal '(', and '[#00ff00]' colors the translated text
        return originalMessage + " ([#00ff00]" + result.translatedText() + "])";
    }
}
