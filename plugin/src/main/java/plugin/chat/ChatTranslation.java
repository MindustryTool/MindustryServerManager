package plugin.chat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import arc.Core;
import arc.Events;
import arc.util.Log;
import arc.util.Strings;
import dto.TranslationRequestDto;
import dto.TranslationResponseDto;
import lombok.RequiredArgsConstructor;
import mindustry.Vars;
import mindustry.game.EventType.PlayerChatEvent;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import plugin.annotations.Component;
import plugin.annotations.Init;
import plugin.gateway.ApiGateway;
import plugin.utils.Utils;

@Component
@RequiredArgsConstructor 
public class ChatTranslation {

    private final ApiGateway apiGateway;

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

            // Immediately send back to sender without translation, resetting color with [white]
            String senderFormatted = formatMessage(player.name, message, Strings.stripColors(message).trim(), null, null);
            player.sendMessage(senderFormatted, player, Strings.stripColors(senderFormatted));

            // Snapshot other online players and their locales on the main thread
            List<Player> targetPlayers = new ArrayList<>();
            Set<String> neededLangs = new HashSet<>();
            Groups.player.forEach(p -> {
                if (p != player) {
                    targetPlayers.add(p);
                    String lang = Utils.parseLocale(p.locale()).getLanguage();
                    if (!lang.isBlank()) {
                        neededLangs.add(lang);
                    }
                }
            });

            // Log chat and fire PlayerChatEvent for other event listeners (e.g. Discord bridges)
            Log.info("<Chat> @: @", Strings.stripColors(player.name), Strings.stripColors(message));
            Events.fire(new PlayerChatEvent(player, message));

            if (!targetPlayers.isEmpty()) {
                // Execute translation asynchronously to prevent blocking the game thread
                CompletableFuture.runAsync(() -> handleAsyncTranslation(player, message, targetPlayers, neededLangs));
            }

            // Return null to suppress default synchronous broadcast
            return null;
        });
    }

    private void handleAsyncTranslation(Player sender, String message, List<Player> targetPlayers, Set<String> neededLangs) {
        try {
            String cleanText = Strings.stripColors(message).trim();
            Map<String, TranslationResult> translations = new HashMap<>();

            if (apiGateway == null || !apiGateway.isConnected()) {
                Log.debug("ApiGateway disconnected, bypassing chat translation");
                dispatchMessages(sender, message, cleanText, targetPlayers, translations);
                return;
            }

            if (!cleanText.isEmpty()) {
                Log.debug("Chat translation processing for neededLangs: @, message: '@'", neededLangs, cleanText);
                for (String lang : neededLangs) {
                    try {
                        TranslationResponseDto res = apiGateway.sendRequest(
                                "translate",
                                new TranslationRequestDto(cleanText, lang),
                                TranslationResponseDto.class
                        ).get(8, TimeUnit.SECONDS);

                        if (res != null && res.getTranslatedText() != null && !res.getTranslatedText().isBlank()) {
                            translations.put(lang, new TranslationResult(res.getTranslatedText(), res.getSourceLanguage()));
                            Log.debug("Chat translation mapped for lang '@': '@'", lang, res.getTranslatedText());
                        } else {
                            Log.debug("Chat translation returned null for lang '@'", lang);
                        }
                    } catch (Exception e) {
                        Log.warn("Failed translating for lang '@': @", lang, e.getMessage());
                    }
                }
            }

            dispatchMessages(sender, message, cleanText, targetPlayers, translations);
        } catch (Exception e) {
            Log.err("Error in chat translation worker", e);
            // Fallback: send original message directly on main thread
            Core.app.post(() -> {
                for (Player recipient : targetPlayers) {
                    if (recipient.isAdded() && recipient.con != null) {
                        String fallback = (sender.name == null || sender.name.isBlank()) ? message : sender.name + "[white]: " + message;
                        recipient.sendMessage(fallback, sender, Strings.stripColors(fallback));
                    }
                }
            });
        }
    }

    private void dispatchMessages(Player sender, String message, String cleanText, List<Player> targetPlayers, Map<String, TranslationResult> translations) {
        Core.app.post(() -> {
            for (Player recipient : targetPlayers) {
                if (!recipient.isAdded() || recipient.con == null) {
                    continue;
                }

                String lang = Utils.parseLocale(recipient.locale()).getLanguage();
                TranslationResult res = translations.get(lang);

                String formatted = formatMessage(sender.name, message, cleanText, lang, res);
                recipient.sendMessage(formatted, sender, Strings.stripColors(formatted));
            }
        });
    }

    public static String formatMessage(String originalMessage, String cleanText, String recipientLang, TranslationResult result) {
        return formatMessage(null, originalMessage, cleanText, recipientLang, result);
    }

    public static String formatMessage(String senderName, String originalMessage, String cleanText, String recipientLang, TranslationResult result) {
        String baseMessage = (senderName == null || senderName.isBlank()) ? originalMessage : senderName + "[white]: " + originalMessage;

        if (result == null || result.translatedText() == null || result.translatedText().isBlank()) {
            return baseMessage;
        }

        // If detected source language is already the recipient's language, no translation needed
        if (result.sourceLanguage() != null && result.sourceLanguage().equalsIgnoreCase(recipientLang)) {
            return baseMessage;
        }

        // If translated text is identical to cleaned original, no translation needed
        if (result.translatedText().equalsIgnoreCase(cleanText)) {
            return baseMessage;
        }

        // Format as: <sender>: <original> ([#00ff00]<translated>)
        // In Mindustry, '([' renders as a literal '(', and '[#00ff00]' colors the translated text
        return baseMessage + " ([#00ff00]" + result.translatedText() + "])";
    }
}
