package com.examora.config;

import com.examora.model.ExamRoom;
import com.examora.model.ExamRoomMemberStatus;
import com.examora.model.Role;
import com.examora.model.User;
import com.examora.repository.ExamCentreRoomRepository;
import com.examora.repository.ExamRepository;
import com.examora.repository.ExamRoomMemberRepository;
import com.examora.repository.ExamRoomRepository;
import com.examora.repository.UserRepository;
import com.examora.security.JwtService;
import com.examora.service.LoginRateLimiter;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.server.HandshakeInterceptor;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final ExamRepository examRepository;
    private final ExamRoomRepository examRoomRepository;
    private final ExamRoomMemberRepository examRoomMemberRepository;
    private final ExamCentreRoomRepository centreRoomRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final LoginRateLimiter connectRateLimiter;
    private final String[] allowedOrigins;

    public WebSocketConfig(JwtService jwtService, UserRepository userRepository,
                           ExamRepository examRepository,
                           ExamRoomRepository examRoomRepository,
                           ExamRoomMemberRepository examRoomMemberRepository,
                           ExamCentreRoomRepository centreRoomRepository,
                           ApplicationEventPublisher eventPublisher,
                           LoginRateLimiter connectRateLimiter,
                           @Value("${examora.cors.allowed-origins:}") List<String> allowedOrigins) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.examRepository = examRepository;
        this.examRoomRepository = examRoomRepository;
        this.examRoomMemberRepository = examRoomMemberRepository;
        this.centreRoomRepository = centreRoomRepository;
        this.eventPublisher = eventPublisher;
        this.connectRateLimiter = connectRateLimiter;
        this.allowedOrigins = allowedOrigins.toArray(new String[0]);
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOrigins(allowedOrigins)
                .addInterceptors(new OriginGuard(allowedOrigins, connectRateLimiter));
    }

    private static final class OriginGuard implements HandshakeInterceptor {
        private final List<String> allowedOrigins;
        private final LoginRateLimiter connectRateLimiter;

        OriginGuard(String[] allowedOrigins, LoginRateLimiter connectRateLimiter) {
            this.allowedOrigins = List.of(allowedOrigins);
            this.connectRateLimiter = connectRateLimiter;
        }

        @Override
        public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                       WebSocketHandler wsHandler, Map<String, Object> attributes) {
            if (allowedOrigins.isEmpty()) {
                return true;
            }
            String origin = request.getHeaders().getFirst(HttpHeaders.ORIGIN);
            if (origin != null && !allowedOrigins.contains(origin)) {
                return false;
            }
            String ip = remoteIp(request);
            String key = "ws-connect";
            if (!connectRateLimiter.isAllowed(ip, key)) {
                return false;
            }
            connectRateLimiter.recordAttempt(ip, key);
            return true;
        }

        private String remoteIp(ServerHttpRequest request) {
            if (request.getRemoteAddress() == null || request.getRemoteAddress().getAddress() == null) {
                return "unknown";
            }
            return request.getRemoteAddress().getAddress().getHostAddress();
        }

        @Override
        public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Exception exception) {
        }
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.setApplicationDestinationPrefixes("/app");
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new SubscriptionGuard(jwtService, userRepository, examRepository,
                examRoomRepository, examRoomMemberRepository, centreRoomRepository, eventPublisher));
    }

    private static final class SubscriptionGuard implements ChannelInterceptor {
        private final JwtService jwt;
        private final UserRepository users;
        private final ExamRepository exams;
        private final ExamRoomRepository examRooms;
        private final ExamRoomMemberRepository examRoomMembers;
        private final ExamCentreRoomRepository centreRooms;
        private final ApplicationEventPublisher eventPublisher;

        SubscriptionGuard(JwtService jwt, UserRepository users, ExamRepository exams,
                          ExamRoomRepository examRooms, ExamRoomMemberRepository examRoomMembers,
                          ExamCentreRoomRepository centreRooms,
                          ApplicationEventPublisher eventPublisher) {
            this.jwt = jwt;
            this.users = users;
            this.exams = exams;
            this.examRooms = examRooms;
            this.examRoomMembers = examRoomMembers;
            this.centreRooms = centreRooms;
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
            if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
                String destination = accessor.getDestination();
                if (destination != null && destination.startsWith("/topic/exams/") && destination.endsWith("/activity")) {
                    String examId = extractExamId(destination);
                    if (examId == null || examId.isBlank()) {
                        throw new AccessDeniedException("Invalid exam topic destination.");
                    }
                    if (account.role() == Role.STUDENT) {
                        throw new AccessDeniedException("Student access is denied for exam monitoring.");
                    }
                    if (account.role() == Role.TEACHER) {
                        boolean ownsExam = exams.findOwnerId(examId)
                                .map(ownerId -> ownerId.equals(account.id()))
                                .orElse(false);
                        if (!ownsExam) {
                            throw new AccessDeniedException("You do not have access to this exam's monitoring.");
                        }
                    }
                }
                if (destination != null && destination.startsWith("/topic/exam-rooms/") && destination.endsWith("/activity")) {
                    guardRoomSubscription(extractRoomId(destination), account);
                }
                if (destination != null && destination.startsWith("/topic/exam-rooms/") && destination.endsWith("/webrtc")) {
                    guardWebRtcSubscription(extractRoomId(destination), account);
                }
                if (destination != null && destination.startsWith("/topic/rooms/") && destination.endsWith("/activity")) {
                    guardCentreRoomSubscription(extractCentreRoomId(destination), account);
                }
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

        private String extractExamId(String destination) {
            String prefix = "/topic/exams/";
            String suffix = "/activity";
            if (!destination.startsWith(prefix) || !destination.endsWith(suffix)) {
                return null;
            }
            return destination.substring(prefix.length(), destination.length() - suffix.length());
        }

        private void guardRoomSubscription(String roomId, User account) {
            if (roomId == null || roomId.isBlank()) {
                throw new AccessDeniedException("Invalid exam room topic destination.");
            }
            ExamRoom room = examRooms.findById(roomId)
                    .orElseThrow(() -> new AccessDeniedException("Exam room not found."));
            if (account.role() == Role.ADMIN) {
                return;
            }
            if (account.role() == Role.TEACHER) {
                boolean ownsExam = exams.findOwnerId(room.examId())
                        .map(ownerId -> ownerId.equals(account.id()))
                        .orElse(false);
                if (!ownsExam) {
                    throw new AccessDeniedException("You do not have access to this exam room.");
                }
                return;
            }
            boolean joined = examRoomMembers
                    .findForStudentByRoom(room.id(), account.id(), ExamRoomMemberStatus.JOINED)
                    .isPresent();
            if (!joined) {
                throw new AccessDeniedException("You have not joined this exam room.");
            }
        }

        private void guardCentreRoomSubscription(String roomId, User account) {
            if (roomId == null || roomId.isBlank()) {
                throw new AccessDeniedException("Invalid room topic destination.");
            }
            com.examora.model.ExamCentreRoom room = centreRooms.findById(roomId)
                    .orElseThrow(() -> new AccessDeniedException("Room not found."));
            if (account.role() == Role.ADMIN) {
                return;
            }
            if (account.role() == Role.TEACHER
                    && room.invigilatorId() != null
                    && room.invigilatorId().equals(account.id())) {
                return;
            }
            throw new AccessDeniedException("Only the room invigilator may monitor this room.");
        }

        private String extractCentreRoomId(String destination) {
            String prefix = "/topic/rooms/";
            String suffix = "/activity";
            if (!destination.startsWith(prefix) || !destination.endsWith(suffix)) {
                return null;
            }
            return destination.substring(prefix.length(), destination.length() - suffix.length());
        }

        private void guardWebRtcSubscription(String roomId, User account) {
            if (roomId == null || roomId.isBlank()) {
                throw new AccessDeniedException("Invalid exam room topic destination.");
            }
            ExamRoom room = examRooms.findById(roomId)
                    .orElseThrow(() -> new AccessDeniedException("Exam room not found."));
            if (account.role() == Role.ADMIN) {
                return;
            }
            if (account.role() == Role.TEACHER) {
                boolean ownsExam = exams.findOwnerId(room.examId())
                        .map(ownerId -> ownerId.equals(account.id()))
                        .orElse(false);
                if (!ownsExam) {
                    throw new AccessDeniedException("You do not have access to this exam room.");
                }
                return;
            }
            throw new AccessDeniedException("Screen sharing is only visible to the exam proctor.");
        }

        private String extractRoomId(String destination) {
            String prefix = "/topic/exam-rooms/";
            if (!destination.startsWith(prefix)) {
                return null;
            }
            String rest = destination.substring(prefix.length());
            int slash = rest.indexOf('/');
            if (slash <= 0) {
                return null;
            }
            return rest.substring(0, slash);
        }
    }
}