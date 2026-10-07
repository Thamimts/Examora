package com.examora.dto;

public final class CentreDtos {
    private CentreDtos() {
    }

    public record CentreRequest(String name, String code, String address, String contactPhone,
                                String contactEmail, String status) {
    }

    public record CentreResponse(String id, String name, String code, String address, String contactPhone,
                                 String contactEmail, String status, int roomCount,
                                 int totalCapacity, int assignedSeats, int availableSeats) {
    }

    public record CentreRoomRequest(String centreId, String roomName, String roomCode, int capacity,
                                    String invigilatorId, String status) {
    }

    public record CentreRoomResponse(String id, String centreId, String roomName, String roomCode,
                                     int capacity, String invigilatorId, String invigilatorName,
                                     String status, int occupied, int availableSeats) {
    }
}