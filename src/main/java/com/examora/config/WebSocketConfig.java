package com.examora.config;

import com.examora.model.Role;
import com.examora.model.User;
import com.examora.repository.UserRepository;
import com.examora.security.JwtService;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.messaging.SessionConnectedEvent;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final String[] allowedOrigins;

    public WebSocketConfig(JwtService jwtService, UserRepository userRepository,
                           ApplicationEventPublisher eventPublisher,
                           @Value("${examora.cors.allowed-origins:}") List<String> allowedOrigins) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.eventPublisher = eventPublisher;
        this.allowedOrigins = allowedOrigins.toArray(new String[0]);
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOrigins(allowedOrigins);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new SubscriptionGuard(jwtService, userRepository, eventPublisher));
    }

    private static final class SubscriptionGuard implements ChannelInterceptor {
        private final JwtService jwt;
        private final UserRepository users;
        private final ApplicationEventPublisher eventPublisher;

        SubscriptionGuard(JwtService jwt, UserRepository users, ApplicationEventPublisher eventPublisher) {
            this.jwt = jwt;
            this.users = users;
            this.eventPublisher = eventPublisher;
        }

        @Override
        public Message<?> preSend(Message<?> message, MessageChannel channel) {
            StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
            if (StompCommand.CONNECT.equals(accessor.getCommand())) {
                authenticateConnect(message, accessor);
                return rebuild(message, accessor);
            }
            User account = sessionUser(accessor);
            if (account == null) {
                if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
                    throw new AccessDeniedException("Authentication is required.");
                }
                return message;
            }
            if (accessor.getUser() == null) {
                accessor.setUser(account::id);
            }
            if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())
                    && "/topic/admin/activity".equals(accessor.getDestination())
                    && account.role() != Role.ADMIN) {
                throw new AccessDeniedException("Administrator access is required.");
            }
            return accessor.getUser() == null ? message : rebuild(message, accessor);
        }

        private Message<?> rebuild(Message<?> message, StompHeaderAccessor accessor) {
            return org.springframework.messaging.support.MessageBuilder
                    .createMessage(((byte[]) message.getPayload()), accessor.getMessageHeaders());
        }

        private void authenticateConnect(Message<?> message, StompHeaderAccessor accessor) {
            String authorization = accessor.getFirstNativeHeader("authorization");
            if (authorization == null || !authorization.startsWith("Bearer ")) {
                throw new AccessDeniedException("Authentication is required.");
            }
            String token = authorization.substring("Bearer ".length());
            User user;
            try {
                user = users.findByEmail(jwt.validate(token).email())
                        .orElseThrow(() -> new AccessDeniedException("Unknown account."));
            } catch (RuntimeException ex) {
                throw new AccessDeniedException("Authentication is required.", ex);
            }
            accessor.setUser(user::id);
            if (accessor.getSessionAttributes() != null) {
                accessor.getSessionAttributes().put("user", user);
            }
            eventPublisher.publishEvent(new SessionConnectedEvent(this, (org.springframework.messaging.Message<byte[]>) message, user::id));
        }

        private User sessionUser(StompHeaderAccessor accessor) {
            Map<String, Object> attributes = accessor.getSessionAttributes();
            if (attributes == null || !(attributes.get("user") instanceof User user)) {
                return null;
            }
            return user;
        }
    }
}