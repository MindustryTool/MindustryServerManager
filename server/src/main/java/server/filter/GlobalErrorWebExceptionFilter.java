package server.filter;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import server.utils.ApiError;

@Component
@Order(-2)
@Slf4j
public class GlobalErrorWebExceptionFilter implements WebExceptionHandler {

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.empty();
        }

        if (ex instanceof ApiError err) {
            exchange.getResponse().setStatusCode(err.getStatus());

            var body = ("{\"error\": \"" + err.getMessage() + "\"}").getBytes();

            return exchange.getResponse()
                    .writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
        }

        return Mono.error(ex);
    }
}
