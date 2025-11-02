package server.controller;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
        return gatewayService.of(id).flatMap(gateway -> gateway.getServer().getState());
    }
}
