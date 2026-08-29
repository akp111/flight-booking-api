package com.flightbooking.controller;

import com.flightbooking.model.Booking;
import com.flightbooking.model.BookingRequest;
import com.flightbooking.model.BookingStatus;
import com.flightbooking.model.Flight;
import com.flightbooking.model.FlightRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("POST/DELETE /api/bookings")
class BookingControllerTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    @DisplayName("booking answers 201, prices the seats, and decrements the flight")
    void bookSeats() {
        Long flightId = createFlight(3);

        ResponseEntity<Booking> response = rest.postForEntity(
                "/api/v1/bookings", bookingFor(flightId, 2), Booking.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Booking booking = response.getBody();
        assertThat(booking).isNotNull();
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(booking.getSeats()).isEqualTo(2);
        assertThat(booking.getTotalPrice()).isEqualByComparingTo("10800.00");
        assertThat(booking.getBookingReference()).startsWith("FB-");
        assertThat(booking.getCancelledAt()).isNull();
        assertThat(response.getHeaders().getLocation())
                .hasToString("/api/v1/bookings/" + booking.getBookingReference());
        assertThat(availableSeats(flightId)).isEqualTo(1);
    }

    @Test
    @DisplayName("overbooking answers 409 and does not consume seats")
    void overbookingIsRefused() {
        Long flightId = createFlight(3);
        rest.postForEntity("/api/v1/bookings", bookingFor(flightId, 2), Booking.class);

        ResponseEntity<String> response = rest.postForEntity(
                "/api/v1/bookings", bookingFor(flightId, 2), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("has only 1 seat(s) available, requested 2");
        assertThat(availableSeats(flightId))
                .as("a refused booking must not consume seats")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a sold-out flight refuses further bookings")
    void soldOutFlightIsRefused() {
        Long flightId = createFlight(2);
        rest.postForEntity("/api/v1/bookings", bookingFor(flightId, 2), Booking.class);

        ResponseEntity<String> response = rest.postForEntity(
                "/api/v1/bookings", bookingFor(flightId, 1), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(availableSeats(flightId)).isZero();
    }

    @Test
    @DisplayName("booking an unknown flight answers 404")
    void unknownFlightIsRefused() {
        ResponseEntity<String> response = rest.postForEntity(
                "/api/v1/bookings", bookingFor(999999L, 1), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("Flight 999999 not found");
    }

    @Test
    @DisplayName("booking fewer than one seat answers 400")
    void zeroSeatsIsRefused() {
        Long flightId = createFlight(3);

        ResponseEntity<String> response = rest.postForEntity(
                "/api/v1/bookings", bookingFor(flightId, 0), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("seats must be at least 1");
        assertThat(availableSeats(flightId)).isEqualTo(3);
    }

    @Test
    @DisplayName("a booking with no passenger email answers 400")
    void missingPassengerEmailIsRefused() {
        Long flightId = createFlight(3);
        BookingRequest noEmail = new BookingRequest(flightId, "Ashis Pradhan", "  ", 1);

        ResponseEntity<String> response = rest.postForEntity("/api/v1/bookings", noEmail, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("passengerEmail is required");
    }

    @Test
    @DisplayName("cancelling answers 200 with CANCELLED and gives the seats back")
    void cancelBooking() {
        Long flightId = createFlight(3);
        String reference = bookAndGetReference(flightId, 2);
        assertThat(availableSeats(flightId)).isEqualTo(1);

        ResponseEntity<Booking> response = rest.exchange(
                "/api/v1/bookings/" + reference, HttpMethod.DELETE, null, Booking.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(response.getBody().getCancelledAt()).isNotNull();
        assertThat(availableSeats(flightId)).isEqualTo(3);
    }

    @Test
    @DisplayName("cancelling twice answers 409 and does not release the seats again")
    void cancelTwiceIsRefused() {
        Long flightId = createFlight(3);
        String reference = bookAndGetReference(flightId, 2);
        rest.exchange("/api/v1/bookings/" + reference, HttpMethod.DELETE, null, Booking.class);

        ResponseEntity<String> response = rest.exchange(
                "/api/v1/bookings/" + reference, HttpMethod.DELETE, null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("is already cancelled");
        assertThat(availableSeats(flightId))
                .as("a second cancellation must not invent seats")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("cancelling an unknown reference answers 404")
    void cancelUnknownReference() {
        ResponseEntity<String> response = rest.exchange(
                "/api/v1/bookings/FB-NOPE1", HttpMethod.DELETE, null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("Booking FB-NOPE1 not found");
    }

    @Test
    @DisplayName("seats freed by a cancellation can be booked again")
    void seatsAreRebookableAfterCancellation() {
        Long flightId = createFlight(3);
        String reference = bookAndGetReference(flightId, 3);
        assertThat(rest.postForEntity("/api/v1/bookings", bookingFor(flightId, 1), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        rest.exchange("/api/v1/bookings/" + reference, HttpMethod.DELETE, null, Booking.class);

        assertThat(rest.postForEntity("/api/v1/bookings", bookingFor(flightId, 3), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(availableSeats(flightId)).isZero();
    }

    @Test
    @DisplayName("no endpoint exposes booking data for retrieval")
    void bookingRetrievalIsNotExposed() {
        Long flightId = createFlight(3);
        String reference = bookAndGetReference(flightId, 1);

        ResponseEntity<String> byReference = rest.getForEntity("/api/v1/bookings/" + reference, String.class);
        ResponseEntity<String> collection = rest.getForEntity("/api/v1/bookings", String.class);
        ResponseEntity<String> byEmail = rest.getForEntity(
                "/api/v1/bookings?email=ashis@example.com", String.class);

        assertThat(byReference.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(collection.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(byEmail.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);

        // The error body echoes the request path, so it may repeat a reference the caller already
        // knew. What must never come back is the booking itself.
        assertThat(byReference.getBody()).doesNotContain("passengerName", "totalPrice", "bookedAt");
        assertThat(collection.getBody()).doesNotContain("bookingReference", "passengerName");
        assertThat(byEmail.getBody()).doesNotContain("bookingReference", "passengerName");
    }

    @Test
    @DisplayName("a missing flightId is refused with 400")
    void rejectsMissingFlightId() {
        BookingRequest bad = new BookingRequest(null, "Ashis Pradhan", "ashis@example.com", 1);

        ResponseEntity<String> response = rest.postForEntity("/api/v1/bookings", bad, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("flightId is required");
    }

    @Test
    @DisplayName("an email that is not an address is refused with 400")
    void rejectsMalformedEmail() {
        Long flightId = createFlight(3);
        BookingRequest bad = new BookingRequest(flightId, "Ashis Pradhan", "not-an-email", 1);

        ResponseEntity<String> response = rest.postForEntity("/api/v1/bookings", bad, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("passengerEmail must be a valid email address");
        assertThat(availableSeats(flightId)).isEqualTo(3);
    }

    @Test
    @DisplayName("a blank passenger name is refused with 400")
    void rejectsBlankPassengerName() {
        Long flightId = createFlight(3);
        BookingRequest bad = new BookingRequest(flightId, "   ", "ashis@example.com", 1);

        ResponseEntity<String> response = rest.postForEntity("/api/v1/bookings", bad, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("passengerName is required");
    }

    @Test
    @DisplayName("validation runs before inventory, so a rejected booking never touches seats")
    void validationRunsBeforeInventory() {
        Long flightId = createFlight(1);
        BookingRequest bad = new BookingRequest(flightId, "", "nope", 0);

        ResponseEntity<String> response = rest.postForEntity("/api/v1/bookings", bad, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody())
                .contains("passengerEmail must be a valid email address")
                .contains("passengerName is required")
                .contains("seats must be at least 1");
        assertThat(availableSeats(flightId)).isEqualTo(1);
    }

    @Test
    @DisplayName("concurrent HTTP bookings never oversell the flight")
    void concurrentHttpBookingsNeverOversell() throws Exception {
        int capacity = 5;
        int contenders = 60;
        Long flightId = createFlight(capacity);

        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<HttpStatusCode>> results = new ArrayList<>();
        try {
            for (int i = 0; i < contenders; i++) {
                int index = i;
                results.add(pool.submit(() -> {
                    start.await();
                    return rest.postForEntity("/api/v1/bookings",
                                    new BookingRequest(flightId, "Racer " + index,
                                            "racer" + index + "@example.com", 1), String.class)
                            .getStatusCode();
                }));
            }
            start.countDown();

            int created = 0;
            int refused = 0;
            for (Future<HttpStatusCode> result : results) {
                HttpStatusCode status = result.get(30, TimeUnit.SECONDS);
                if (status.isSameCodeAs(HttpStatus.CREATED)) {
                    created++;
                } else {
                    assertThat(status).isEqualTo(HttpStatus.CONFLICT);
                    refused++;
                }
            }

            assertThat(created)
                    .as("%d concurrent requests must not sell more than %d seats", contenders, capacity)
                    .isEqualTo(capacity);
            assertThat(refused).isEqualTo(contenders - capacity);
            assertThat(availableSeats(flightId)).isZero();
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    private Long createFlight(int totalSeats) {
        Flight created = rest.postForObject("/api/v1/flights", new FlightRequest(
                "AI-202", "Air India", "DEL", "BOM",
                LocalDateTime.of(2026, 9, 15, 8, 30), LocalDateTime.of(2026, 9, 15, 10, 45),
                new BigDecimal("5400.00"), totalSeats), Flight.class);
        assertThat(created).isNotNull();
        return created.getId();
    }

    private BookingRequest bookingFor(Long flightId, int seats) {
        return new BookingRequest(flightId, "Ashis Pradhan", "ashis@example.com", seats);
    }

    private String bookAndGetReference(Long flightId, int seats) {
        Booking booking = rest.postForObject("/api/v1/bookings", bookingFor(flightId, seats), Booking.class);
        assertThat(booking).isNotNull();
        return booking.getBookingReference();
    }

    private int availableSeats(Long flightId) {
        Flight flight = rest.getForObject("/api/v1/flights/" + flightId, Flight.class);
        assertThat(flight).isNotNull();
        return flight.getAvailableSeats();
    }
}
