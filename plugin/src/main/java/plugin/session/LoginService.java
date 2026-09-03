package plugin.session;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

import arc.util.Log;
import dto.LoginDto;
import lombok.RequiredArgsConstructor;
import mindustry.gen.Player;
import plugin.annotations.Component;
import plugin.annotations.Destroy;
import plugin.gateway.ApiGateway;

@Component
@RequiredArgsConstructor
public class LoginService {
    public static final int LINK_EXPIRATION_SECONDS = 5 * 60; // 5 minutes

    private final ApiGateway apiGateway;
    private final ConcurrentHashMap<String, LoginLink> links = new ConcurrentHashMap<>();

    public static class LoginLink {
        public final String url;
        public final Instant generatedAt;

        public LoginLink(String url) {
            this.url = url;
            this.generatedAt = Instant.now();
        }

        public boolean isExpired() {
            return Instant.now().isAfter(generatedAt.plusSeconds(LINK_EXPIRATION_SECONDS));
        }

        public int getRemainingSeconds() {
            long elapsed = Instant.now().getEpochSecond() - generatedAt.getEpochSecond();
            return Math.max(0, (int) (LINK_EXPIRATION_SECONDS - elapsed));
        }
    }

    public synchronized LoginLink getOrGenerateLink(Player player) {
        LoginLink existing = links.get(player.uuid());
        if (existing != null && !existing.isExpired() && existing.url != null && !existing.url.isEmpty()) {
            return existing;
        }

        try {
            LoginDto login = apiGateway.login(player);
            String url = login != null ? login.getLoginLink() : null;
            LoginLink newLink = new LoginLink(url);
            links.put(player.uuid(), newLink);
            return newLink;
        } catch (Exception e) {
            Log.err("Failed to generate login link for player @", player.name, e);
            return null;
        }
    }

    public synchronized void invalidate(Player player) {
        if (player != null) {
            links.remove(player.uuid());
        }
    }

    @Destroy
    public void destroy() {
        links.clear();
    }
}
