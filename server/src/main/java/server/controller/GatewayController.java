package server.controller;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;

import dto.ServerStateDto;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;
import server.service.GatewayService;

@RestController
@RequestMapping("gateway/v2")
@RequiredArgsConstructor
public class GatewayController {

    private final GatewayService gatewayService;

    @GetMapping("servers/{id}/request-connection")
    public Mono<ServerStateDto> getServers(@PathVariable("id") UUID id) {
        return gatewayService.of(id).flatMap(gateway -> gateway.server().getState());
    }

    @PostMapping("servers/{id}/login")
    public Mono<JsonNode> login(@PathVariable("id") UUID id, @RequestBody JsonNode body) {
        return gatewayService.getApi().login(id, body);
    }

    @PostMapping("servers/{id}/host")
    public Mono<String> host(@PathVariable("id") UUID id) {
        return gatewayService.getApi().host(id);
    }
}
