package com.flightbooking.service;

import com.flightbooking.model.Booking;
import com.flightbooking.model.BookingRequest;
import com.flightbooking.model.BookingStatus;
import com.flightbooking.model.Flight;
import com.flightbooking.model.FlightRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("BookingService")
class BookingServiceTest {

    private FlightService flightService;
    private BookingService bookingService;

    @BeforeEach
    void setUp() {
        flightService = new FlightService();
        bookingService = new BookingService(flightService);
    }

    @Test
    @DisplayName("a booking is confirmed, priced, and takes its seats out of inventory")
    void bookingConfirmsAndDecrements() {
        Flight flight = createFlight(3);

        Booking booking = book(flight, 2);

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(booking.getSeats()).isEqualTo(2);
        assertThat(booking.getFlightId()).isEqualTo(flight.getId());
        assertThat(booking.getTotalPrice()).isEqualByComparingTo("10800.00");
        assertThat(booking.getBookingReference()).startsWith("FB-");
        assertThat(booking.getBookedAt()).isNotNull();
        assertThat(booking.getCancelledAt()).isNull();
        assertThat(flight.getAvailableSeats()).isEqualTo(1);
    }

    @Test
    @DisplayName("booking more seats than remain is refused and leaves inventory untouched")
    void refusesOverbooking() {
        Flight flight = createFlight(3);
        book(flight, 2);

        assertStatus(() -> book(flight, 2), HttpStatus.CONFLICT);

        assertThat(flight.getAvailableSeats())
                .as("a refused booking must not consume seats")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a sold-out flight refuses even a single seat")
    void refusesBookingOnSoldOutFlight() {
        Flight flight = createFlight(2);
        book(flight, 2);

        assertStatus(() -> book(flight, 1), HttpStatus.CONFLICT);

        assertThat(flight.getAvailableSeats()).isZero();
    }

    @Test
    @DisplayName("the overbooking message says how many seats are actually left")
    void overbookingMessageExplainsWhy() {
        Flight flight = createFlight(3);
        book(flight, 2);

        assertThatThrownBy(() -> book(flight, 2))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("has only 1 seat(s) available, requested 2");
    }

    @Test
    @DisplayName("rejects a booking for fewer than one seat")
    void rejectsNonPositiveSeats() {
        Flight flight = createFlight(3);

        assertStatus(() -> book(flight, 0), HttpStatus.BAD_REQUEST);
        assertStatus(() -> book(flight, -2), HttpStatus.BAD_REQUEST);
        assertThat(flight.getAvailableSeats()).isEqualTo(3);
    }

    @Test
    @DisplayName("rejects a booking on an unknown flight")
    void rejectsUnknownFlight() {
        assertStatus(() -> bookingService.create(
                new BookingRequest(999L, "Ashis", "ashis@example.com", 1)), HttpStatus.NOT_FOUND);
        assertStatus(() -> bookingService.create(
                new BookingRequest(null, "Ashis", "ashis@example.com", 1)), HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("rejects a booking with no passenger name or email")
    void rejectsMissingPassengerDetails() {
        Flight flight = createFlight(3);

        assertStatus(() -> bookingService.create(
                new BookingRequest(flight.getId(), "  ", "ashis@example.com", 1)), HttpStatus.BAD_REQUEST);
        assertStatus(() -> bookingService.create(
                new BookingRequest(flight.getId(), "Ashis", null, 1)), HttpStatus.BAD_REQUEST);
        assertThat(flight.getAvailableSeats()).isEqualTo(3);
    }

    @Test
    @DisplayName("cancelling returns the seats to the flight")
    void cancelReleasesSeats() {
        Flight flight = createFlight(3);
        Booking booking = book(flight, 2);

        Booking cancelled = bookingService.cancel(booking.getBookingReference());

        assertThat(cancelled.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(cancelled.getCancelledAt()).isNotNull();
        assertThat(flight.getAvailableSeats()).isEqualTo(3);
    }

    @Test
    @DisplayName("cancelling twice is refused and does not release the seats again")
    void cancelIsNotRepeatable() {
        Flight flight = createFlight(3);
        Booking booking = book(flight, 2);
        bookingService.cancel(booking.getBookingReference());

        assertStatus(() -> bookingService.cancel(booking.getBookingReference()), HttpStatus.CONFLICT);

        assertThat(flight.getAvailableSeats())
                .as("a second cancellation must not invent seats")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("cancelling an unknown reference is a 404")
    void cancelUnknownReference() {
        assertStatus(() -> bookingService.cancel("FB-NOPE"), HttpStatus.NOT_FOUND);
        assertStatus(() -> bookingService.cancel(null), HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("a booking reference is accepted in any case")
    void referenceLookupIsCaseInsensitive() {
        Flight flight = createFlight(3);
        Booking booking = book(flight, 1);

        Booking cancelled = bookingService.cancel(
                "  " + booking.getBookingReference().toLowerCase() + "  ");

        assertThat(cancelled.getBookingReference()).isEqualTo(booking.getBookingReference());
    }

    @Test
    @DisplayName("released seats can be booked by someone else")
    void seatsAreRebookableAfterCancellation() {
        Flight flight = createFlight(3);
        Booking first = book(flight, 3);
        assertStatus(() -> book(flight, 1), HttpStatus.CONFLICT);

        bookingService.cancel(first.getBookingReference());
        Booking second = book(flight, 3);

        assertThat(second.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(flight.getAvailableSeats()).isZero();
    }

    @Test
    @DisplayName("every booking gets a distinct reference")
    void referencesAreUnique() {
        Flight flight = createFlight(500);

        List<String> references = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            references.add(book(flight, 1).getBookingReference());
        }

        assertThat(references).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("200 threads racing for 50 seats sell exactly 50 — never more")
    void concurrentBookingsNeverOversell() throws Exception {
        int capacity = 50;
        int contenders = 200;
        Flight flight = createFlight(capacity);

        List<Future<Booking>> results = runConcurrently(contenders,
                index -> bookingService.create(new BookingRequest(
                        flight.getId(), "Racer " + index, "racer" + index + "@example.com", 1)));

        int confirmed = 0;
        int refused = 0;
        for (Future<Booking> result : results) {
            try {
                result.get();
                confirmed++;
            } catch (ExecutionException e) {
                assertThat(e.getCause()).isInstanceOf(ResponseStatusException.class);
                assertThat(((ResponseStatusException) e.getCause()).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT);
                refused++;
            }
        }

        assertThat(confirmed).as("exactly the aircraft capacity is sold").isEqualTo(capacity);
        assertThat(refused).isEqualTo(contenders - capacity);
        assertThat(flight.getAvailableSeats()).isZero();
    }

    /**
     * The scenario above spends most of its time far from the boundary, where a sloppy
     * implementation still looks correct. This one keeps every thread fighting over the last
     * seat or two, which is the only place a check-then-act race can show itself: if the
     * availability test is not inside the same lock as the decrement, several threads read the
     * same positive count and all of them commit.
     */
    @RepeatedTest(value = 25, name = "round {currentRepetition} of {totalRepetitions}")
    @DisplayName("a nearly-full flight never oversells under maximum contention")
    void contentionOnTheLastSeatsNeverOversells() throws Exception {
        int capacity = 2;
        Flight flight = createFlight(capacity);

        List<Future<Booking>> results = runConcurrently(64,
                index -> bookingService.create(new BookingRequest(
                        flight.getId(), "Racer " + index, "racer" + index + "@example.com", 1)));

        int confirmed = 0;
        for (Future<Booking> result : results) {
            try {
                result.get();
                confirmed++;
            } catch (ExecutionException e) {
                assertThat(((ResponseStatusException) e.getCause()).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT);
            }
        }

        assertThat(confirmed)
                .as("64 threads chasing %d seats must not sell more than %d", capacity, capacity)
                .isEqualTo(capacity);
        assertThat(flight.getAvailableSeats())
                .as("inventory must never go negative")
                .isZero();
    }

    @Test
    @DisplayName("concurrent bookings of mixed sizes never oversell the aircraft")
    void concurrentMixedSizeBookingsNeverOversell() throws Exception {
        int capacity = 50;
        Flight flight = createFlight(capacity);

        List<Future<Booking>> results = runConcurrently(200,
                index -> bookingService.create(new BookingRequest(
                        flight.getId(), "Racer " + index, "racer" + index + "@example.com",
                        (index % 3) + 1)));

        int sold = 0;
        for (Future<Booking> result : results) {
            try {
                sold += result.get().getSeats();
            } catch (ExecutionException e) {
                assertThat(((ResponseStatusException) e.getCause()).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT);
            }
        }

        assertThat(sold).as("seats sold never exceed capacity").isLessThanOrEqualTo(capacity);
        assertThat(flight.getAvailableSeats()).isNotNegative();
        assertThat(sold + flight.getAvailableSeats())
                .as("every seat is either sold or available — none invented, none lost")
                .isEqualTo(capacity);
    }

    @Test
    @DisplayName("cancellations racing new bookings conserve every seat")
    void concurrentCancellationsAndBookingsConserveSeats() throws Exception {
        int capacity = 40;
        Flight flight = createFlight(capacity);

        List<Booking> initial = new ArrayList<>();
        for (int i = 0; i < capacity; i++) {
            initial.add(book(flight, 1));
        }
        assertThat(flight.getAvailableSeats()).isZero();

        // Each existing booking is cancelled twice over, while 100 new bookings compete for
        // whatever those cancellations free up.
        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Booking>> cancellations = new ArrayList<>();
        List<Future<Booking>> newBookings = new ArrayList<>();
        try {
            for (Booking booking : initial) {
                for (int attempt = 0; attempt < 2; attempt++) {
                    cancellations.add(pool.submit(() -> {
                        start.await();
                        return bookingService.cancel(booking.getBookingReference());
                    }));
                }
            }
            for (int i = 0; i < 100; i++) {
                int index = i;
                newBookings.add(pool.submit(() -> {
                    start.await();
                    return bookingService.create(new BookingRequest(
                            flight.getId(), "Late " + index, "late" + index + "@example.com", 1));
                }));
            }
            start.countDown();

            int cancelled = countSuccesses(cancellations);
            int booked = countSuccesses(newBookings);

            assertThat(cancelled)
                    .as("each booking cancels once; the duplicate attempt is refused")
                    .isEqualTo(capacity);

            int seatsHeld = capacity - cancelled + booked;
            assertThat(seatsHeld + flight.getAvailableSeats())
                    .as("no seat is invented by a double cancellation or lost by a failed booking")
                    .isEqualTo(capacity);
            assertThat(flight.getAvailableSeats()).isBetween(0, capacity);
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    private int countSuccesses(List<Future<Booking>> futures) throws Exception {
        int successes = 0;
        for (Future<Booking> future : futures) {
            try {
                future.get();
                successes++;
            } catch (ExecutionException e) {
                assertThat(e.getCause())
                        .as("the only acceptable failure is a refusal, never a crash")
                        .isInstanceOf(ResponseStatusException.class);
                assertThat(((ResponseStatusException) e.getCause()).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT);
            }
        }
        return successes;
    }

    private interface IndexedCall {
        Booking apply(int index) throws Exception;
    }

    /** Fires {@code count} calls that all block on one latch, so they hit the service together. */
    private List<Future<Booking>> runConcurrently(int count, IndexedCall call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Booking>> results = new ArrayList<>();
        try {
            for (int i = 0; i < count; i++) {
                int index = i;
                results.add(pool.submit(() -> {
                    start.await();
                    return call.apply(index);
                }));
            }
            start.countDown();
            for (Future<Booking> result : results) {
                try {
                    result.get(30, TimeUnit.SECONDS);
                } catch (ExecutionException ignored) {
                    // Inspected by the caller.
                }
            }
            return results;
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    private Flight createFlight(int totalSeats) {
        return flightService.create(new FlightRequest("AI-202", "Air India", "DEL", "BOM",
                LocalDateTime.of(2026, 9, 15, 8, 30), LocalDateTime.of(2026, 9, 15, 10, 45),
                new BigDecimal("5400.00"), totalSeats));
    }

    private Booking book(Flight flight, int seats) {
        return bookingService.create(
                new BookingRequest(flight.getId(), "Ashis Pradhan", "ashis@example.com", seats));
    }

    private static void assertStatus(Runnable action, HttpStatusCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOf(ResponseStatusException.class)
                .extracting(thrown -> ((ResponseStatusException) thrown).getStatusCode())
                .isEqualTo(expected);
    }
}
