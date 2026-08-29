package com.flightbooking.model;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Payload for {@code POST /api/bookings}. The total price is derived from the flight's per-seat
 * price rather than taken from the caller.
 */
public record BookingRequest(
        @NotNull(message = "flightId is required")
        Long flightId,

        @NotBlank(message = "passengerName is required")
        String passengerName,

        @NotBlank(message = "passengerEmail is required")
        @Email(message = "passengerEmail must be a valid email address")
        String passengerEmail,

        @Positive(message = "seats must be at least 1")
        int seats) {
}
