package server.filter;

import java.util.UUID;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import server.EnvConfig;
import server.types.data.ServerManagerJwt;
import server.utils.ApiError;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;

import arc.util.Log;

@Component
@Order(200)
@Slf4j
@RequiredArgsConstructor
public class SecurityFilter implements WebFilter {
    private static final String ISSUER = "MindustryTool";

    private final EnvConfig envConfig;

    public static final Class<?> CONTEXT_KEY = ServerManagerJwt.class;

    public static Mono<ServerManagerJwt> getContext() {
        return Mono.deferContextual(Mono::just)//
                .cast(Context.class)//
                .filter(SecurityFilter::hasContext)//
                .flatMap(SecurityFilter::getContext);
    }

    private static boolean hasContext(Context context) {
        return context.hasKey(CONTEXT_KEY);
    }

    private static Mono<ServerManagerJwt> getContext(Context context) {
        return context.<Mono<ServerManagerJwt>>get(CONTEXT_KEY);
    }

    public static Context withRequest(Mono<? extends ServerManagerJwt> request) {
        return Context.of(CONTEXT_KEY, request);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String uri = exchange.getRequest().getURI().getPath();

        if (uri.isEmpty() || uri.equals("/")) {
            return chain.filter(exchange);
        }

        String securityKey = envConfig.serverConfig().securityKey();

        if (securityKey == null) {
            return ApiError.forbidden("Security token is not set");
        }

        String accessToken = exchange.getRequest().getHeaders().getFirst("Authorization");

        if (accessToken == null) {
            log.info("No access token found");
            return ApiError.unauthorized();
        }

        String token = accessToken.replace("Bearer ", "");

        try {
            var data = getDataFromToken(token, securityKey);

            return chain.filter(exchange).contextWrite(withRequest(Mono.just(data)));
        } catch (Exception e) {
            Log.err("Invalid token: " + token + " security key: " + securityKey, e);
            return ApiError.forbidden("Invalid token or security key");
        }
    }

    public ServerManagerJwt getDataFromToken(String token, String secret) {
        var claims = JWT.require(Algorithm.HMAC256(secret))//
                .withIssuer(ISSUER)//
                .build()//
                .verify(token)//
                .getClaims();

        try {
            return new ServerManagerJwt().setId(UUID.fromString(claims.get("id").asString()))
                    .setUserId(UUID.fromString(claims.get("userId").asString()));
        } catch (Exception e) {
            throw new IllegalStateException("Invalid token data: " + claims, e);
        }
    }
}
