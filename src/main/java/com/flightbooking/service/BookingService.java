package com.flightbooking.service;

import com.flightbooking.model.Booking;
import com.flightbooking.model.BookingRequest;
import com.flightbooking.model.BookingStatus;
import com.flightbooking.model.Flight;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Owns bookings, keyed by their public booking reference, and drives seat inventory on
 * {@link FlightService} as bookings are created and cancelled.
 */
@Service
public class BookingService {

    private static final String REFERENCE_PREFIX = "FB-";

    private final FlightService flightService;
    private final Map<String, Booking> bookings = new ConcurrentHashMap<>();
    private final AtomicLong idSequence = new AtomicLong();

    public BookingService(FlightService flightService) {
        this.flightService = flightService;
    }

    public Booking create(BookingRequest request) {
        if (request.seats() < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "seats must be at least 1");
        }
        if (isBlank(request.passengerName())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "passengerName is required");
        }
        if (isBlank(request.passengerEmail())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "passengerEmail is required");
        }

        Flight flight = flightService.findById(request.flightId());
        BigDecimal totalPrice = flight.getPrice().multiply(BigDecimal.valueOf(request.seats()));

        // Hold the seats first, so a booking never exists without inventory behind it.
        flightService.reserveSeats(flight.getId(), request.seats());
        try {
            long id = idSequence.incrementAndGet();
            Booking booking;
            // putIfAbsent both stores the booking and rejects a reference collision in one step.
            do {
                booking = new Booking(
                        id,
                        generateReference(),
                        flight.getId(),
                        request.passengerName().trim(),
                        request.passengerEmail().trim(),
                        request.seats(),
                        totalPrice,
                        BookingStatus.CONFIRMED,
                        LocalDateTime.now(),
                        null);
            } while (bookings.putIfAbsent(booking.getBookingReference(), booking) != null);
            return booking;
        } catch (RuntimeException e) {
            // Nothing was recorded, so give the seats back rather than leaking inventory.
            flightService.releaseSeats(flight.getId(), request.seats());
            throw e;
        }
    }

    /**
     * Cancels a booking and returns its seats to the flight.
     *
     * <p>The already-cancelled check, the seat release and the status flip run inside
     * {@code computeIfPresent}, so two concurrent cancellations of the same reference cannot both
     * release seats — the second sees {@code CANCELLED} and fails with 409.
     */
    public Booking cancel(String reference) {
        Booking cancelled = bookings.computeIfPresent(normalise(reference), (ref, booking) -> {
            if (booking.getStatus() == BookingStatus.CANCELLED) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Booking " + ref + " is already cancelled");
            }
            flightService.releaseSeats(booking.getFlightId(), booking.getSeats());
            booking.setStatus(BookingStatus.CANCELLED);
            booking.setCancelledAt(LocalDateTime.now());
            return booking;
        });

        if (cancelled == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Booking " + reference + " not found");
        }
        return cancelled;
    }

    private String generateReference() {
        return REFERENCE_PREFIX + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
    }

    /** References are issued in upper case, so accept them in any case. */
    private String normalise(String reference) {
        return reference == null ? "" : reference.trim().toUpperCase(Locale.ROOT);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
