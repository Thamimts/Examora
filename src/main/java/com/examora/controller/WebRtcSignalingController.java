package com.examora.controller;

import com.examora.dto.WebRtcDtos.WebRtcSignalRequest;
import com.examora.model.User;
import com.examora.service.WebRtcSignalingService;
import java.util.Map;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;

@Controller
public class WebRtcSignalingController {
    private final WebRtcSignalingService signalingService;

    public WebRtcSignalingController(WebRtcSignalingService signalingService) {
        this.signalingService = signalingService;
    }

    @MessageMapping("/exam-rooms/{roomId}/webrtc")
    public void signal(@DestinationVariable String roomId,
                       @Payload(required = false) WebRtcSignalRequest signal,
                       @Header(value = SimpMessageHeaderAccessor.SESSION_ATTRIBUTES, required = false) Map<String, Object> sessionAttributes) {
        User actor = sessionAttributes == null ? null
                : sessionAttributes.get("user") instanceof User user ? user : null;
        if (actor == null) {
            throw new AccessDeniedException("Authentication is required.");
        }
        signalingService.handle(roomId, signal, actor);
    }
}