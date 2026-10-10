package com.mindtechpy.nexus.infrastructure.config;

import com.mindtechpy.nexus.application.port.out.ClockPort;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
class SystemClock implements ClockPort {

    @Override
    public Instant now() {
        return Instant.now();
    }
}
