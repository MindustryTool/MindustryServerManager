package plugin.chat;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import arc.util.Log;
import dto.TranslationRequestDto;
import dto.TranslationResponseDto;
import plugin.annotations.Component;
import plugin.annotations.Init;
import plugin.core.Registry;
import plugin.gateway.ApiGateway;

@Component
public class GoogleWebProvider implements TranslationProvider {

    private static final Duration BASE_COOLDOWN = Duration.ofSeconds(5);
    private static final Duration MAX_COOLDOWN = Duration.ofMinutes(5);

    private final ApiGateway apiGateway;
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private volatile Instant cooldownUntil = Instant.MIN;

    public GoogleWebProvider() {
        this(Registry.getOrNull(ApiGateway.class));
    }

    public GoogleWebProvider(ApiGateway apiGateway) {
        this.apiGateway = apiGateway;
    }

    @Init
    private void init() {
        TranslationService service = Registry.getOrNull(TranslationService.class);
        if (service != null) {
            service.registerProvider(this);
        }
    }

    @Override
    public String name() {
        return "google-web";
    }

    @Override
    public int getOrder() {
        return 100; // Primary provider
    }

    @Override
    public boolean isAvailable() {
        if (isCooldownActive()) {
            return false;
        }
        return isGatewayConnected();
    }

    public boolean isCooldownActive() {
        return Instant.now().isBefore(cooldownUntil);
    }

    public void triggerCooldown() {
        int failures = failureCount.incrementAndGet();
        long multiplier = 1L << Math.min(failures - 1, 6);
        long seconds = Math.min(MAX_COOLDOWN.toSeconds(), BASE_COOLDOWN.toSeconds() * multiplier);
        this.cooldownUntil = Instant.now().plusSeconds(seconds);
        Log.warn("GoogleWebProvider placed in cooldown for @s (failures: @) until @", seconds, failures, cooldownUntil);
    }

    public void resetCooldown() {
        this.failureCount.set(0);
        this.cooldownUntil = Instant.MIN;
    }

    public int getFailureCount() {
        return failureCount.get();
    }

    public Instant getCooldownUntil() {
        return cooldownUntil;
    }

    protected boolean isGatewayConnected() {
        ApiGateway gw = getGateway();
        return gw != null && gw.isConnected();
    }

    private ApiGateway getGateway() {
        if (apiGateway != null) {
            return apiGateway;
        }
        return Registry.getOrNull(ApiGateway.class);
    }

    @Override
    public TranslationResult translate(String text, String targetLang) throws Exception {
        if (isCooldownActive()) {
            throw new IllegalStateException("GoogleWebProvider is currently in cooldown until " + cooldownUntil);
        }

        ApiGateway gw = getGateway();
        if (gw == null || !isGatewayConnected()) {
            throw new IllegalStateException("ApiGateway is disconnected from server manager");
        }

        try {
            TranslationResponseDto response = gw.sendRequest(
                    "translate",
                    new TranslationRequestDto(text, targetLang),
                    TranslationResponseDto.class
            ).get(8, TimeUnit.SECONDS);

            if (response == null || response.getTranslatedText() == null || response.getTranslatedText().isBlank()) {
                return null;
            }

            failureCount.set(0);
            return new TranslationResult(response.getTranslatedText(), response.getSourceLanguage());
        } catch (Exception e) {
            triggerCooldown();
            throw e;
        }
    }
}
