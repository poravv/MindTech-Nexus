package com.mindtechpy.nexus.application.port.out;

import java.time.Instant;

public interface ClockPort {

    Instant now();
}
