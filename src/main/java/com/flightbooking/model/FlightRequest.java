package com.flightbooking.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Payload for {@code POST /api/flights}. {@code availableSeats} is not accepted from the caller —
 * a new flight always starts with every seat free.
 */
public record FlightRequest(
        String flightNumber,
        String airline,
        String origin,
        String destination,
        LocalDateTime departureTime,
        LocalDateTime arrivalTime,
        BigDecimal price,
        int totalSeats) {
}
