package com.examora.dto;

import com.examora.model.ProctorEvent;
import java.util.List;

public final class ProctorDtos {
    private ProctorDtos() {
    }

    public record EventBatchRequest(List<ProctorEvent> events) {
    }
}
