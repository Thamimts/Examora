package com.examora.config;

import com.examora.model.Role;
import com.examora.model.User;
import com.examora.repository.UserRepository;
import com.examora.security.JwtService;
import java.security.Principal;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    private final JwtService jwtService; private final UserRepository userRepository;
    public WebSocketConfig(JwtService jwtService, UserRepository userRepository) { this.jwtService = jwtService; this.userRepository = userRepository; }
    @Override public void registerStompEndpoints(StompEndpointRegistry registry) { registry.addEndpoint("/ws").setAllowedOriginPatterns("*").setHandshakeHandler(new JwtHandshakeHandler()).addInterceptors(new JwtHandshake(jwtService, userRepository)); }
    @Override public void configureMessageBroker(org.springframework.messaging.simp.config.MessageBrokerRegistry registry) { registry.enableSimpleBroker("/topic", "/queue"); registry.setUserDestinationPrefix("/user"); }
    @Override public void configureClientInboundChannel(ChannelRegistration registration) { registration.interceptors(new SubscriptionGuard()); }

    private static final class JwtHandshake implements HandshakeInterceptor {
        private final JwtService jwt; private final UserRepository users;
        JwtHandshake(JwtService jwt, UserRepository users) { this.jwt = jwt; this.users = users; }
        @Override public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler, java.util.Map<String, Object> attributes) {
            String query = request.getURI().getQuery(); String token = query == null ? null : java.util.Arrays.stream(query.split("&")).filter(part -> part.startsWith("token=")).map(part -> java.net.URLDecoder.decode(part.substring(6), java.nio.charset.StandardCharsets.UTF_8)).findFirst().orElse(null);
            if (token == null) return false;
            try { User user = users.findByEmail(jwt.validate(token).email()).orElse(null); if (user == null) return false; attributes.put("user", user); attributes.put("principal", (Principal) user::id); return true; } catch (RuntimeException ex) { return false; }
        }
        @Override public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler, Exception exception) {}
    }
    private static final class JwtHandshakeHandler extends DefaultHandshakeHandler {
        @Override protected Principal determineUser(ServerHttpRequest request, WebSocketHandler handler, java.util.Map<String, Object> attributes) { return (Principal) attributes.get("principal"); }
    }
    private static final class SubscriptionGuard implements ChannelInterceptor {
        @Override public Message<?> preSend(Message<?> message, MessageChannel channel) {
            StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
            if (StompCommand.CONNECT.equals(accessor.getCommand()) && accessor.getUser() == null) throw new org.springframework.security.access.AccessDeniedException("Authentication is required.");
            if (StompCommand.SUBSCRIBE.equals(accessor.getCommand()) && "/topic/admin/activity".equals(accessor.getDestination())) {
                Object user = accessor.getSessionAttributes() == null ? null : accessor.getSessionAttributes().get("user");
                if (!(user instanceof User account) || account.role() != Role.ADMIN) throw new org.springframework.security.access.AccessDeniedException("Administrator access is required.");
            }
            return message;
        }
    }
}
