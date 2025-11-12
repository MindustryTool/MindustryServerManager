package server.filter;

import java.time.Duration;
import java.time.Instant;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import server.utils.Utils;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
@RequiredArgsConstructor
public class RequestFilter implements WebFilter {
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var start = Instant.now();

        return chain.filter(exchange)//
                .doOnTerminate(() -> {
                    var request = exchange.getRequest();
                    String requestUrl = request.getURI().toString();
                    var status = exchange.getResponse().getStatusCode();
                    var method = request.getMethod();

                    var message = "[%s] [%s] %s %s".formatted(
                            Utils.toReadableString(Duration.between(start, Instant.now())),
                            status == null ? "Unknown" : status.value(),
                            method.toString().toUpperCase(), requestUrl);

                    if (status.value() >= 500) {
                        log.error(message);
                    } else if (status.value() >= 400) {
                        log.warn(message);
                    } else {
                        log.info(message);
                    }
                });
    }

}
