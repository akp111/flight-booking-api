package com.flightbooking.model;

/**
 * Payload for {@code POST /api/bookings}. The total price is derived from the flight's per-seat
 * price rather than taken from the caller.
 */
public record BookingRequest(
        Long flightId,
        String passengerName,
        String passengerEmail,
        int seats) {
}
