package com.examora.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CreateExamRequest(
        @NotBlank(message = "Title is required.") String title,
        @NotBlank(message = "Subject is required.") String subject,
        @NotBlank(message = "Date is required.") @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "Date must use yyyy-MM-dd format.") String date,
        @Min(value = 1, message = "Duration must be at least 1 minute.")
        @Max(value = 300, message = "Duration must not exceed 300 minutes.") int duration
) {
}
