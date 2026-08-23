package com.examora.backend.test;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/test")
public class TestController {

    @GetMapping("/public")
    public String publicEndpoint() {
        return "Public endpoint is working";
    }

    @GetMapping("/protected")
    public String protectedEndpoint(
            Authentication authentication
    ) {
        return "Hello " +
                authentication.getName() +
                ", JWT authentication is working!";
    }
}
