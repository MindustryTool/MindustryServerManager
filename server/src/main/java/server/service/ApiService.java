package server.service;

import java.net.URI;
import java.time.Duration;

import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;
import server.utils.Utils;

@Service
@RequiredArgsConstructor
public class ApiService {
    private final WebClient webClient = WebClient.builder()
            .codecs(configurer -> configurer
                    .defaultCodecs()
                    .maxInMemorySize(16 * 1024 * 1024))
            .baseUrl(URI.create("https://api.mindustry-tool.com/api/v4/")
                    .toString())
            .defaultStatusHandler(Utils::handleStatus, Utils::createError)
            .build();

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
