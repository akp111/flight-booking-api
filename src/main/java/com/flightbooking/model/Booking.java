package com.flightbooking.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A confirmed or cancelled seat reservation on a {@link Flight}.
 *
 * <p>{@code bookingReference} is the public handle callers use; {@code id} is the internal
 * insertion order. Mutable because {@code status} and {@code cancelledAt} change on cancellation.
 */
public class Booking {

    private Long id;
    private String bookingReference;
    private Long flightId;
    private String passengerName;
    private String passengerEmail;
    private int seats;
    private BigDecimal totalPrice;
    private BookingStatus status;
    private LocalDateTime bookedAt;
    private LocalDateTime cancelledAt;

    public Booking() {
    }

    public Booking(Long id, String bookingReference, Long flightId, String passengerName,
                   String passengerEmail, int seats, BigDecimal totalPrice, BookingStatus status,
                   LocalDateTime bookedAt, LocalDateTime cancelledAt) {
        this.id = id;
        this.bookingReference = bookingReference;
        this.flightId = flightId;
        this.passengerName = passengerName;
        this.passengerEmail = passengerEmail;
        this.seats = seats;
        this.totalPrice = totalPrice;
        this.status = status;
        this.bookedAt = bookedAt;
        this.cancelledAt = cancelledAt;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getBookingReference() {
        return bookingReference;
    }

    public void setBookingReference(String bookingReference) {
        this.bookingReference = bookingReference;
    }

    public Long getFlightId() {
        return flightId;
    }

    public void setFlightId(Long flightId) {
        this.flightId = flightId;
    }

    public String getPassengerName() {
        return passengerName;
    }

    public void setPassengerName(String passengerName) {
        this.passengerName = passengerName;
    }

    public String getPassengerEmail() {
        return passengerEmail;
    }

    public void setPassengerEmail(String passengerEmail) {
        this.passengerEmail = passengerEmail;
    }

    public int getSeats() {
        return seats;
    }

    public void setSeats(int seats) {
        this.seats = seats;
    }

    public BigDecimal getTotalPrice() {
        return totalPrice;
    }

    public void setTotalPrice(BigDecimal totalPrice) {
        this.totalPrice = totalPrice;
    }

    public BookingStatus getStatus() {
        return status;
    }

    public void setStatus(BookingStatus status) {
        this.status = status;
    }

    public LocalDateTime getBookedAt() {
        return bookedAt;
    }

    public void setBookedAt(LocalDateTime bookedAt) {
        this.bookedAt = bookedAt;
    }

    public LocalDateTime getCancelledAt() {
        return cancelledAt;
    }

    public void setCancelledAt(LocalDateTime cancelledAt) {
        this.cancelledAt = cancelledAt;
    }
}
