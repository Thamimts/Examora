package com.examora.service;

import com.examora.model.ProctorEvent;
import com.examora.repository.ProctorRepository;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ProctorService {
    private final ProctorRepository proctorRepository;

    public ProctorService(ProctorRepository proctorRepository) {
        this.proctorRepository = proctorRepository;
    }

    public int saveBatch(List<ProctorEvent> events) {
        return proctorRepository.saveBatch(events);
    }
}
