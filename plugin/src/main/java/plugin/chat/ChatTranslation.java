package plugin.chat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import arc.Core;
import arc.util.Log;
import arc.util.Strings;
import common.ratelimit.KeyedRateLimiter;
import common.translation.TranslationRequest;
import common.translation.TranslationResponse;
import lombok.RequiredArgsConstructor;
import mindustry.Vars;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import plugin.annotations.Component;
import plugin.annotations.Init;
import plugin.gateway.ApiGateway;
import plugin.utils.Utils;

@Component
@RequiredArgsConstructor
public class ChatTranslation {

    private static final double RATE_LIMIT_BURST = 5;
    private static final double RATE_LIMIT_REFILL_PER_SECOND = 1;
    private static final Duration RATE_LIMIT_IDLE_TTL = Duration.ofMinutes(5);

    private final ApiGateway apiGateway;
    private final KeyedRateLimiter<String> userRateLimiter = new KeyedRateLimiter<>(RATE_LIMIT_BURST,
            RATE_LIMIT_REFILL_PER_SECOND, RATE_LIMIT_IDLE_TTL);

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

            String cleanText = Strings.stripColors(message).trim();
            boolean translationAllowed = userRateLimiter.tryAcquire(player.uuid());

            // Immediately send back to sender without translation, resetting color with [white]
            String senderFormatted = formatMessage(player.name, message, cleanText, null, null);
            player.sendMessage(senderFormatted, player, Strings.stripColors(senderFormatted));

            Log.info(senderFormatted);

            // Snapshot other online players grouped by locale language on the main thread
            List<Player> blankLocalePlayers = new ArrayList<>();
            Map<String, List<Player>> playersByLang = new HashMap<>();
            Groups.player.forEach(p -> {
                if (p != player) {
                    String lang = Utils.parseLocale(p.locale()).getLanguage();
                    if (lang.isBlank()) {
                        blankLocalePlayers.add(p);
                    } else {
                        playersByLang.computeIfAbsent(lang, k -> new ArrayList<>()).add(p);
                    }
                }
            });

            if (!blankLocalePlayers.isEmpty()) {
                dispatchToGroup(player, message, cleanText, null, null, blankLocalePlayers);
            }

            if (playersByLang.isEmpty()) {
                return null;
            }

            if (!translationAllowed) {
                Log.debug("Chat translation rate limited for player '@'", player.name);
                for (List<Player> recipients : playersByLang.values()) {
                    dispatchToGroup(player, message, cleanText, null, null, recipients);
                }
                return null;
            }

            if (apiGateway == null || !apiGateway.isConnected()) {
                Log.debug("ApiGateway disconnected, bypassing chat translation");
                for (List<Player> recipients : playersByLang.values()) {
                    dispatchToGroup(player, message, cleanText, null, null, recipients);
                }
                return null;
            }

            if (cleanText.isEmpty()) {
                for (List<Player> recipients : playersByLang.values()) {
                    dispatchToGroup(player, message, cleanText, null, null, recipients);
                }
                return null;
            }

            // Stream translation requests per language independently without blocking
            playersByLang.forEach((lang, recipients) ->
                    handleAsyncTranslation(player, message, cleanText, lang, recipients));

            // Return null to suppress default synchronous broadcast
            return null;
        });
    }

    void handleAsyncTranslation(Player sender, String message, String cleanText, String lang, List<Player> recipients) {
        apiGateway.sendRequest(
                "translate",
                new TranslationRequest(cleanText, lang),
                TranslationResponse.class
        ).orTimeout(8, TimeUnit.SECONDS)
        .whenComplete((res, ex) -> {
            TranslationResult result = null;
            if (ex != null) {
                Log.warn("Failed translating for lang '@': @", lang, ex.getMessage());
            } else if (res != null && res.getTranslatedText() != null && !res.getTranslatedText().isBlank()) {
                result = new TranslationResult(res.getTranslatedText(), res.getSourceLanguage());
                Log.debug("Chat translation mapped for lang '@': '@'", lang, res.getTranslatedText());
            } else {
                Log.debug("Chat translation returned null for lang '@'", lang);
            }

            dispatchToGroup(sender, message, cleanText, lang, result, recipients);
        });
    }

    void dispatchToGroup(Player sender, String message, String cleanText, String lang, TranslationResult result, List<Player> recipients) {
        Core.app.post(() -> {
            for (Player recipient : recipients) {
                if (!recipient.isAdded() || recipient.con == null) {
                    continue;
                }

                String formatted = formatMessage(sender.name, message, cleanText, lang, result);
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

        return baseMessage + " [#00d47e](" + result.translatedText() + ")";
    }
}
