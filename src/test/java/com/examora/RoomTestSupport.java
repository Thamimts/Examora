package com.examora;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Shared helpers for integration tests that need an Exam Room to be created, joined
 * and/or started before starting an official exam attempt.
 */
public final class RoomTestSupport {

    private RoomTestSupport() {
    }

    public static Room createRoom(MockMvc mockMvc, ObjectMapper objectMapper, String teacherToken, String examId)
            throws Exception {
        String response = mockMvc.perform(post("/api/exam-rooms")
                        .header("Authorization", bearer(teacherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"examId\":\"" + examId + "\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode data = objectMapper.readTree(response).path("data");
        Room room = new Room(data.path("roomId").asText(), data.path("roomCode").asText());
        assertThat(room.id()).isNotBlank();
        assertThat(room.code()).isNotBlank();
        return room;
    }

    public static void joinRoom(MockMvc mockMvc, String studentToken, String roomCode) throws Exception {
        mockMvc.perform(post("/api/exam-rooms/join")
                        .header("Authorization", bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roomCode\":\"" + roomCode + "\"}"))
                .andExpect(status().isOk());
    }

    public static void startRoom(MockMvc mockMvc, String teacherToken, String roomId) throws Exception {
        mockMvc.perform(post("/api/exam-rooms/" + roomId + "/start")
                        .header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk());
    }

    public static void endRoom(MockMvc mockMvc, String teacherToken, String roomId) throws Exception {
        mockMvc.perform(post("/api/exam-rooms/" + roomId + "/end")
                        .header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk());
    }

    /** Creates a WAITING room, joins the student and starts the room (ACTIVE). */
    public static Room createJoinAndStartRoom(MockMvc mockMvc, ObjectMapper objectMapper,
                                              String teacherToken, String examId, String studentToken) throws Exception {
        Room room = createRoom(mockMvc, objectMapper, teacherToken, examId);
        joinRoom(mockMvc, studentToken, room.code());
        startRoom(mockMvc, teacherToken, room.id());
        return room;
    }

    public static String bearer(String token) {
        return "Bearer " + token;
    }

    public record Room(String id, String code) {
    }
}