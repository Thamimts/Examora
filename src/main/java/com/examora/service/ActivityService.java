package com.examora.service;

import com.examora.model.ActivityEvent;
import com.examora.model.Role;
import com.examora.model.User;
import com.examora.repository.ActivityRepository;
import java.util.List;
import java.util.UUID;
import com.examora.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

@Service
public class ActivityService {
    private final ActivityRepository repository;
    private final SimpMessagingTemplate messaging;
    public ActivityService(ActivityRepository repository, SimpMessagingTemplate messaging) { this.repository = repository; this.messaging = messaging; }
    public List<ActivityEvent> recent(User user) {
        if (user.role() == Role.ADMIN) return repository.findForAdmin();
        if (user.role() == Role.STUDENT) return repository.findForStudent(user.id());
        throw new ApiException(HttpStatus.FORBIDDEN, "Student or administrator access is required.");
    }
    public void student(User user, String type, String message) { publish(repository.create(UUID.randomUUID().toString(), user.id(), "STUDENT", type, message), user.id(), false); }
    public void admin(String type, String message) { publish(repository.create(UUID.randomUUID().toString(), null, "ADMIN", type, message), null, true); }
    public void admin(User actor, String type, String message) { publish(repository.create(UUID.randomUUID().toString(), actor.id(), "ADMIN", type, message), null, true); }
    private void publish(ActivityEvent event, String studentId, boolean admin) {
        if (admin) messaging.convertAndSend("/topic/admin/activity", event);
        else messaging.convertAndSendToUser(studentId, "/queue/activity", event);
    }
}
