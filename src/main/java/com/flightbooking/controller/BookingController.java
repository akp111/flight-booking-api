package com.flightbooking.controller;

import com.flightbooking.model.Booking;
import com.flightbooking.model.BookingRequest;
import com.flightbooking.service.BookingService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/api/${api.version}/bookings")
public class BookingController {

    private final BookingService bookingService;
    private final String apiVersion;

    public BookingController(BookingService bookingService, @Value("${api.version}") String apiVersion) {
        this.bookingService = bookingService;
        this.apiVersion = apiVersion;
    }

    @PostMapping
    public ResponseEntity<Booking> create(@Valid @RequestBody BookingRequest request) {
        Booking booking = bookingService.create(request);
        URI location = URI.create("/api/" + apiVersion + "/bookings/" + booking.getBookingReference());
        return ResponseEntity.created(location).body(booking);
    }

    /** Cancels the booking and returns it, so the caller sees the new status without a second call. */
    @DeleteMapping("/{reference}")
    public Booking cancel(@PathVariable String reference) {
        return bookingService.cancel(reference);
    }
}
