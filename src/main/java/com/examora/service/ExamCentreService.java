package com.examora.service;

import com.examora.dto.CentreDtos.CentreRequest;
import com.examora.dto.CentreDtos.CentreResponse;
import com.examora.dto.CentreDtos.CentreRoomRequest;
import com.examora.dto.CentreDtos.CentreRoomResponse;
import com.examora.exception.ApiException;
import com.examora.model.ExamCentre;
import com.examora.model.ExamCentreRoom;
import com.examora.model.Role;
import com.examora.model.User;
import com.examora.repository.ExamCentreRepository;
import com.examora.repository.ExamCentreRoomRepository;
import com.examora.repository.UserRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Administration of exam centres and the physical rooms inside them. Room/seat occupancy is
 * handled by {@link RoomSeatingService}; this service manages the physical configuration.
 */
@Service
public class ExamCentreService {
    private final ExamCentreRepository centreRepository;
    private final ExamCentreRoomRepository roomRepository;
    private final UserRepository userRepository;
    private final JdbcTemplate jdbcTemplate;

    public ExamCentreService(ExamCentreRepository centreRepository, ExamCentreRoomRepository roomRepository,
                             UserRepository userRepository, JdbcTemplate jdbcTemplate) {
        this.centreRepository = centreRepository;
        this.roomRepository = roomRepository;
        this.userRepository = userRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<CentreResponse> centres(User actor) {
        requireStaff(actor);
        return centreRepository.findAll().stream()
                .map(centre -> toResponse(centre))
                .toList();
    }

    public CentreResponse centre(String id, User actor) {
        requireStaff(actor);
        ExamCentre centre = requireCentre(id);
        return toResponse(centre);
    }

    public CentreResponse createCentre(CentreRequest request, User actor) {
        requireAdmin(actor);
        if (request == null || isBlank(request.name())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Centre name is required.");
        }
        String code = request.code() == null || request.code().isBlank()
                ? generateCode(request.name()) : request.code().trim();
        ExamCentre created;
        try {
            created = centreRepository.create(new ExamCentre(
                    UUID.randomUUID().toString(),
                    request.name().trim(),
                    code,
                    trimToNull(request.address()),
                    trimToNull(request.contactPhone()),
                    trimToNull(request.contactEmail()),
                    request.status() == null || request.status().isBlank() ? "ACTIVE" : request.status().trim().toUpperCase(),
                    actor.id(),
                    null, null));
        } catch (DuplicateKeyException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "A centre with this code already exists.");
        }
        return toResponse(created);
    }

    public CentreResponse updateCentre(String id, CentreRequest request, User actor) {
        requireAdmin(actor);
        ExamCentre existing = requireCentre(id);
        if (request == null || isBlank(request.name())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Centre name is required.");
        }
        ExamCentre updated = new ExamCentre(existing.id(), request.name().trim(),
                request.code() == null || request.code().isBlank() ? existing.code() : request.code().trim(),
                request.address() == null ? existing.address() : trimToNull(request.address()),
                request.contactPhone() == null ? existing.contactPhone() : trimToNull(request.contactPhone()),
                request.contactEmail() == null ? existing.contactEmail() : trimToNull(request.contactEmail()),
                request.status() == null || request.status().isBlank() ? existing.status() : request.status().trim().toUpperCase(),
                existing.createdBy(), existing.createdAt(), existing.updatedAt());
        try {
            if (centreRepository.update(id, updated) == 0) {
                throw new ApiException(HttpStatus.NOT_FOUND, "Centre not found.");
            }
        } catch (DuplicateKeyException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "A centre with this code already exists.");
        }
        return toResponse(requireCentre(id));
    }

    public void deleteCentre(String id, User actor) {
        requireAdmin(actor);
        if (centreRepository.delete(id) == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Centre not found.");
        }
    }

    public List<CentreRoomResponse> roomsForCentre(String centreId, User actor) {
        requireStaff(actor);
        requireCentre(centreId);
        Map<String, Integer> occupiedByRoom = occupiedByRoom();
        return roomRepository.findByCentreId(centreId).stream()
                .map(room -> toRoomResponse(room, occupiedByRoom.getOrDefault(room.id(), 0)))
                .toList();
    }

    public List<CentreRoomResponse> myInvigilatorRooms(User actor) {
        requireTeacherOrAdmin(actor);
        Map<String, Integer> occupiedByRoom = occupiedByRoom();
        List<ExamCentreRoom> rooms = actor.role() == Role.ADMIN
                ? roomRepository.findActive()
                : roomRepository.findByInvigilatorId(actor.id());
        return rooms.stream()
                .map(room -> toRoomResponse(room, occupiedByRoom.getOrDefault(room.id(), 0)))
                .toList();
    }

    public CentreRoomResponse createRoom(CentreRoomRequest request, User actor) {
        requireAdmin(actor);
        if (request == null || isBlank(request.centreId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Centre id is required.");
        }
        requireCentre(request.centreId().trim());
        if (isBlank(request.roomName())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Room name is required.");
        }
        if (request.capacity() <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Room capacity must be a positive number.");
        }
        ExamCentreRoom room = new ExamCentreRoom(
                UUID.randomUUID().toString(),
                request.centreId().trim(),
                request.roomName().trim(),
                request.roomCode() == null || request.roomCode().isBlank()
                        ? generateRoomCode() : request.roomCode().trim().toUpperCase(),
                request.capacity(),
                trimToNull(request.invigilatorId()),
                request.status() == null || request.status().isBlank() ? "ACTIVE" : request.status().trim().toUpperCase(),
                null, null);
        try {
            roomRepository.create(room);
        } catch (DuplicateKeyException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "A room with this name or code already exists.");
        }
        return toRoomResponse(room, 0);
    }

    public CentreRoomResponse updateRoom(String id, CentreRoomRequest request, User actor) {
        requireAdmin(actor);
        ExamCentreRoom existing = requireRoom(id);
        if (request == null || isBlank(request.roomName())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Room name is required.");
        }
        if (request.capacity() <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Room capacity must be a positive number.");
        }
        ExamCentreRoom updated = new ExamCentreRoom(
                existing.id(),
                request.centreId() == null || request.centreId().isBlank() ? existing.centreId() : request.centreId().trim(),
                request.roomName().trim(),
                request.roomCode() == null || request.roomCode().isBlank() ? existing.roomCode() : request.roomCode().trim().toUpperCase(),
                request.capacity(),
                request.invigilatorId() == null ? existing.invigilatorId() : trimToNull(request.invigilatorId()),
                request.status() == null || request.status().isBlank() ? existing.status() : request.status().trim().toUpperCase(),
                existing.createdAt(), existing.updatedAt());
        try {
            if (roomRepository.update(id, updated) == 0) {
                throw new ApiException(HttpStatus.NOT_FOUND, "Room not found.");
            }
        } catch (DuplicateKeyException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "A room with this name or code already exists.");
        }
        return toRoomResponse(requireRoom(id), occupiedByRoom().getOrDefault(id, 0));
    }

    public void deleteRoom(String id, User actor) {
        requireAdmin(actor);
        if (roomRepository.delete(id) == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Room not found.");
        }
    }

    public ExamCentreRoom requireRoom(String id) {
        return roomRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Exam room not found."));
    }

    public ExamCentre requireCentre(String id) {
        return centreRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Exam centre not found."));
    }

    public String invigilatorName(ExamCentreRoom room) {
        if (room.invigilatorId() == null) {
            return null;
        }
        return userRepository.findById(room.invigilatorId()).map(User::name).orElse(null);
    }

    private Map<String, Integer> occupiedByRoom() {
        return jdbcTemplate.query("select room_id, count(*) as cnt from room_assignments group by room_id", rs -> {
            Map<String, Integer> result = new java.util.HashMap<>();
            while (rs.next()) {
                result.put(rs.getString(1), rs.getInt(2));
            }
            return result;
        });
    }

    private CentreResponse toResponse(ExamCentre centre) {
        List<ExamCentreRoom> rooms = roomRepository.findByCentreId(centre.id());
        int totalCapacity = 0;
        int assigned = 0;
        Map<String, Integer> occupiedByRoom = occupiedByRoom();
        for (ExamCentreRoom room : rooms) {
            totalCapacity += room.capacity();
            assigned += occupiedByRoom.getOrDefault(room.id(), 0);
        }
        return new CentreResponse(centre.id(), centre.name(), centre.code(), centre.address(),
                centre.contactPhone(), centre.contactEmail(), centre.status(), rooms.size(),
                totalCapacity, assigned, totalCapacity - assigned);
    }

    private CentreRoomResponse toRoomResponse(ExamCentreRoom room, int occupied) {
        return new CentreRoomResponse(room.id(), room.centreId(), room.roomName(), room.roomCode(),
                room.capacity(), room.invigilatorId(), invigilatorName(room), room.status(), occupied,
                room.capacity() - occupied);
    }

    private String generateCode(String name) {
        String base = name.trim().toUpperCase().replaceAll("[^A-Z0-9]", "");
        if (base.length() > 6) {
            base = base.substring(0, 6);
        }
        if (base.length() < 3) {
            base = base + "CTR";
        }
        return base + "-" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    }

    private String generateRoomCode() {
        return "R-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    }

    private void requireStaff(User actor) {
        requireTeacherOrAdmin(actor);
    }

    private void requireAdmin(User actor) {
        if (actor == null || actor.role() != Role.ADMIN) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Administrator access is required.");
        }
    }

    private void requireTeacherOrAdmin(User actor) {
        if (actor == null || (actor.role() != Role.TEACHER && actor.role() != Role.ADMIN)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Teacher or administrator access is required.");
        }
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}