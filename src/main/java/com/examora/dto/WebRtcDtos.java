package com.examora.dto;

import com.fasterxml.jackson.databind.JsonNode;

public final class WebRtcDtos {
    private WebRtcDtos() {
    }

    public record WebRtcSignalRequest(
            String roomId,
            String peerId,
            String type,
            JsonNode sdp,
            JsonNode candidate
    ) {
    }

    public record WebRtcSignalDto(
            String roomId,
            String senderId,
            String senderRole,
            String peerId,
            String type,
            JsonNode sdp,
            JsonNode candidate
    ) {
    }
}