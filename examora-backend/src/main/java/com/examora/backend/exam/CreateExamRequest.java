package com.examora.backend.exam;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateExamRequest(

        @NotBlank(message = "Title is required")
        String title,

        @NotBlank(message = "Subject is required")
        String subject,

        @NotNull(message = "Duration is required")
        @Min(value = 1, message = "Duration must be at least 1 minute")
        Integer durationMinutes

) {
}
