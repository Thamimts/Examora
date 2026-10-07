package com.examora.dto;

public final class EnrollmentDtos {
    private EnrollmentDtos() {
    }

    /**
     * Student self-enrolment. Auto-assigns the next available room/seat for the exam.
     */
    public record EnrollRequest(String examId) {
    }

    /**
     * A student's enrolment decorated with their persistent room/seat assignment and the current
     * state of their (latest) exam attempt. {@code attemptStatus} is derived from the attempt
     * table and is independent of the enrolment status.
     */
    public record EnrollmentDetailDto(String enrollmentId, String examId, String examTitle, String subject,
                                      String status, String enrolledAt,
                                      String assignmentId, String roomId, String roomName, String roomCode,
                                      String centreName, String centreId, Integer seatNumber,
                                      String attemptStatus) {
    }

    /**
     * One row of the enrolment roster for an exam, decorated with the student's seat and their
     * latest attempt status and proctoring risk.
     */
    public record ExamRosterDto(String enrollmentId, String studentId, String studentName, String studentEmail,
                                String rollNumber, String roomId, String roomName, Integer seatNumber,
                                String attemptStatus, String riskLevel) {
    }

    /**
     * A student seated in a room (invigilator view), with their attempt status and risk level.
     */
    public record RoomStudentDto(String assignmentId, String studentId, String studentName, String studentEmail,
                                 String rollNumber, int seatNumber, String status, String riskLevel) {
    }

    /**
     * Invigilator room detail: the room plus its seated students. {@code occupied} is the number
     * of students currently seated in the room for the exam.
     */
    public record RoomSeatingDto(String roomId, String roomName, String roomCode, int capacity,
                                 String centreId, String centreName, String invigilatorId, String invigilatorName,
                                 String status, int occupied, java.util.List<RoomStudentDto> students) {
    }

    /**
     * A room plus one exam that actually seats students in it (from {@code room_assignments}),
     * with live occupancy and attempt/risk summaries for the invigilator dashboards. A room with
     * no seated exam appears once with null exam fields and zero occupancy.
     */
    public record InvigilatorRoomSummaryDto(String roomId, String roomName, String roomCode, String roomStatus,
                                            String centreId, String centreName, int capacity, int occupied,
                                            String examId, String examTitle, String examStatus,
                                            int activeCount, int submittedCount, String riskLevel,
                                            double riskScore, boolean live) {
    }
}