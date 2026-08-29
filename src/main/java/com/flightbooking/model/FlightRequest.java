package com.flightbooking.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Payload for {@code POST /api/flights}. {@code availableSeats} is not accepted from the caller —
 * a new flight always starts with every seat free.
 */
public record FlightRequest(
        @NotBlank(message = "flightNumber is required")
        String flightNumber,

        String airline,

        @NotBlank(message = "origin is required")
        String origin,

        @NotBlank(message = "destination is required")
        String destination,

        @NotNull(message = "departureTime is required")
        LocalDateTime departureTime,

        @NotNull(message = "arrivalTime is required")
        LocalDateTime arrivalTime,

        @NotNull(message = "price is required")
        @PositiveOrZero(message = "price must be zero or greater")
        BigDecimal price,

        @Positive(message = "totalSeats must be at least 1")
        int totalSeats) {
}
