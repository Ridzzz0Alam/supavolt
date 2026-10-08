package dev.supavolt.api.features.realtime;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
class RealtimeConfig implements WebSocketConfigurer {

    private final RealtimeHub hub;

    RealtimeConfig(RealtimeHub hub) {
        this.hub = hub;
    }

    /**
     * Outside the /api prefix, where the frontend's realtime client looks for it. Any origin may
     * connect: the project key in the query string is the credential, and anon keys are public.
     */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(hub, "/realtime").setAllowedOriginPatterns("*");
    }
}
