package server;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import io.netty.resolver.dns.DnsNameResolverTimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import dto.ErrorResponse;
import server.utils.ApiError;
import reactor.core.publisher.Mono;

@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    @ExceptionHandler(WebClientRequestException.class)
    Mono<ResponseEntity<ErrorResponse>> handle(ServerWebExchange exchange, WebClientRequestException exception) {
        var message = exception.getMessage();

        return createResponse(exchange, HttpStatus.BAD_REQUEST, exception, message);
    }

    @ExceptionHandler(WebExchangeBindException.class)
    Mono<ResponseEntity<ErrorResponse>> handle(ServerWebExchange exchange, WebExchangeBindException exception) {
        var message = new StringBuilder("Validation failed\n");

        for (ObjectError error : exception.getAllErrors()) {
            message.append('[').append(error).append("]\n");
        }

        return createResponse(exchange, HttpStatus.BAD_REQUEST, exception, message.toString());
    }

    @ExceptionHandler(AccessDeniedException.class)
    Mono<ResponseEntity<ErrorResponse>> handle(ServerWebExchange exchange, AccessDeniedException exception) {
        return createResponse(exchange, HttpStatus.FORBIDDEN, exception, "Access denied");
    }

    @ExceptionHandler(ResponseStatusException.class)
    Mono<ResponseEntity<ErrorResponse>> handle(ServerWebExchange exchange, ResponseStatusException exception) {
        return createResponse(exchange, HttpStatus.resolve((exception.getStatusCode().value())), exception,
                exception.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    Mono<ResponseEntity<ErrorResponse>> handle(ServerWebExchange exchange, IllegalArgumentException exception) {
        return createResponse(exchange, HttpStatus.BAD_REQUEST, exception, exception.getMessage());
    }

    @ExceptionHandler(ApiError.class)
    Mono<ResponseEntity<ErrorResponse>> handle(ServerWebExchange exchange, ApiError exception) {
        return createResponse(exchange, exception.getStatus(), exception, exception.getMessage());
    }

    @ExceptionHandler({ Exception.class, DnsNameResolverTimeoutException.class })
    Mono<ResponseEntity<ErrorResponse>> handle(ServerWebExchange exchange, Exception exception) {
        return createResponse(exchange, HttpStatus.INTERNAL_SERVER_ERROR, exception, "Internal server error");
    }

    public Mono<ResponseEntity<ErrorResponse>> createResponse(ServerWebExchange exchange, HttpStatus status,
            Exception exception, String message) {

        var request = exchange.getRequest();

        Mono<String> urlMono = Mono.just(request)//
                .map(r -> r.getURI().toString())//
                .defaultIfEmpty("Unknown");

        Mono<String> ipMono = Mono.just(request)//
                .flatMap(r -> Mono.justOrEmpty(r.getHeaders().get("X-Forwarded-For")))//
                .filter(address -> !address.isEmpty())//
                .map(address -> address.get(0))//
                .defaultIfEmpty("Unknown");

        return Mono.zipDelayError(urlMono, ipMono)//
                .map(result -> {
                    var url = result.getT1();

                    var data = ErrorResponse.builder()//
                            .status(status.value())//
                            .message(message)//
                            .url(url)//
                            .build();

                    exception.printStackTrace();

                    return ResponseEntity.status(status).body(data);
                }).onErrorResume(error -> {

                    log.error("Error while creating response", error);
                    return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                            ErrorResponse.builder()//
                                    .status(status.value())//
                                    .message("Error while creating error response")//
                                    .url("unknown")//
                                    .build()));
                });
    }
}
