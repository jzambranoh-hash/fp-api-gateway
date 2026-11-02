package com.nttdata.api_gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.event.Level;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;

/**
 * Access log del gateway: una línea estructurada por petición.
 *
 * <ul>
 *   <li>INFO para 2xx/3xx, WARN para 4xx, ERROR para 5xx.</li>
 *   <li>No registra headers, body ni query string (pueden traer tokens o datos personales).</li>
 *   <li>El traceId lo agrega Micrometer Tracing al MDC; no hay que ponerlo a mano.</li>
 * </ul>
 */
@Slf4j
@Component
public class AccessLogFilter implements GlobalFilter, Ordered {

    private static final int MAX_USER_AGENT = 200;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long start = System.nanoTime();
        return chain.filter(exchange)
                .doFinally(signal -> log(exchange, start, signal.toString()));
    }

    private void log(ServerWebExchange exchange, long start, String signal) {
        ServerHttpRequest request = exchange.getRequest();
        HttpStatusCode status = exchange.getResponse().getStatusCode();
        int code = status != null ? status.value() : 0;
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);

        log.atLevel(levelFor(code))
                .setMessage("Petición atendida")
                .addKeyValue("event", "http.access")
                .addKeyValue("method", request.getMethod().name())
                .addKeyValue("path", request.getPath().value())
                .addKeyValue("route", route != null ? route.getId() : "none")
                .addKeyValue("status", code)
                .addKeyValue("duration_ms", (System.nanoTime() - start) / 1_000_000)
                .addKeyValue("client_ip", clientIp(request))
                .addKeyValue("device", deviceType(request))     // nuevo: resumen para filtrar
                .addKeyValue("user_agent", userAgent(request))  // nuevo: texto completo (recortado)
                .addKeyValue("signal", signal) // ON_COMPLETE, ON_ERROR o CANCEL (cliente cortó)
                .log();
    }

    private static Level levelFor(int status) {
        if (status >= 500 || status == 0) {
            return Level.ERROR;
        }
        return status >= 400 ? Level.WARN : Level.INFO;
    }

    private static String clientIp(ServerHttpRequest request) {
        String forwarded = request.getHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        InetSocketAddress remote = request.getRemoteAddress();
        return remote != null ? remote.getAddress().getHostAddress() : "unknown";
    }

    /** User-Agent recortado: es texto que manda el cliente, no se registra sin límite. */
    private static String userAgent(ServerHttpRequest request) {
        String ua = request.getHeaders().getFirst("User-Agent");
        if (ua == null || ua.isBlank()) {
            return "unknown";
        }
        return ua.length() > MAX_USER_AGENT ? ua.substring(0, MAX_USER_AGENT) : ua;
    }

    /** Clasificación simple para agrupar en Grafana. */
    private static String deviceType(ServerHttpRequest request) {
        String ua = userAgent(request).toLowerCase();
        if (ua.contains("postman")) {
            return "postman";
        }
        if (ua.startsWith("curl") || ua.contains("insomnia") || ua.contains("java") || ua.contains("python")) {
            return "tool";
        }
        if (ua.contains("android") || ua.contains("iphone") || ua.contains("ipad") || ua.contains("mobile")) {
            return "mobile";
        }
        if (ua.contains("mozilla")) {
            return "desktop";
        }
        return "other";
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE; // primero en entrar, último en salir: mide el tiempo total
    }

}
