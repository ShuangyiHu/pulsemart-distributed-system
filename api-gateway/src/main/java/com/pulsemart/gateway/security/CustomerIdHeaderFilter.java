package com.pulsemart.gateway.security;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class CustomerIdHeaderFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return ReactiveSecurityContextHolder.getContext()
                .filter(ctx -> ctx.getAuthentication() instanceof JwtAuthenticationToken)
                .map(ctx -> (JwtAuthenticationToken) ctx.getAuthentication())
                .flatMap(jwt -> {
                    String customerId = jwt.getToken().getClaimAsString("customerId");
                    if (customerId != null) {
                        ServerHttpRequest mutated = exchange.getRequest().mutate()
                                .header("X-Customer-Id", customerId)
                                .build();
                        return chain.filter(exchange.mutate().request(mutated).build());
                    }
                    return chain.filter(exchange);
                })
                .switchIfEmpty(chain.filter(exchange));
    }

    @Override
    public int getOrder() {
        return 1;
    }
}
