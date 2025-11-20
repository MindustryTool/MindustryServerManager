package server.service;

import java.net.URI;
import java.time.Duration;

import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;
import server.EnvConfig;
import server.utils.Utils;

@Service
@RequiredArgsConstructor
public class ApiService {

    private final EnvConfig envConfig;

    private final WebClient webClient = WebClient.builder()
            .codecs(configurer -> configurer
                    .defaultCodecs()
                    .maxInMemorySize(16 * 1024 * 1024))
            .baseUrl(URI.create("https://api.mindustry-tool.com/api/v4/")
                    .toString())
            .defaultStatusHandler(Utils::handleStatus, Utils::createError)
            .build();

    @PostConstruct
    private void init() {
        Utils.wrapError(webClient.method(HttpMethod.GET)
                .uri("managers/request-connection")
                .header("Authorization", "Bearer " + envConfig.serverConfig().accessToken())
                .retrieve()
                .bodyToMono(Void.class), Duration.ofSeconds(5), "Request connection").subscribe();
    }

    public final Mono<byte[]> getMapPreview(byte[] mapData) {

        MultipartBodyBuilder builder = new MultipartBodyBuilder();

        builder.part("file", mapData, MediaType.APPLICATION_OCTET_STREAM).filename("map");

        return Utils.wrapError(webClient.method(HttpMethod.POST)//
                .uri("maps/image")//
                .body(BodyInserters.fromMultipartData(builder.build()))
                .retrieve()//
                .bodyToMono(byte[].class), Duration.ofMinutes(5), "Get map preview");

    }
}
