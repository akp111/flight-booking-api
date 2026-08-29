package com.flightbooking.service;

import com.flightbooking.model.Flight;
import com.flightbooking.model.FlightRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("FlightService")
class FlightServiceTest {

    private FlightService flightService;

    @BeforeEach
    void setUp() {
        flightService = new FlightService();
    }

    @Test
    @DisplayName("a new flight starts with every seat available")
    void newFlightStartsFull() {
        Flight flight = flightService.create(request(120));

        assertThat(flight.getId()).isEqualTo(1L);
        assertThat(flight.getTotalSeats()).isEqualTo(120);
        assertThat(flight.getAvailableSeats()).isEqualTo(120);
        assertThat(flight.getFlightNumber()).isEqualTo("AI-202");
    }

    @Test
    @DisplayName("ids are handed out in sequence")
    void idsIncrement() {
        assertThat(flightService.create(request(10)).getId()).isEqualTo(1L);
        assertThat(flightService.create(request(10)).getId()).isEqualTo(2L);
    }

    @Test
    @DisplayName("rejects a flight with no seats")
    void rejectsZeroSeats() {
        assertStatus(() -> flightService.create(request(0)), HttpStatus.BAD_REQUEST);
        assertStatus(() -> flightService.create(request(-5)), HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("rejects a missing or negative price")
    void rejectsBadPrice() {
        FlightRequest negative = new FlightRequest("AI-202", "Air India", "DEL", "BOM",
                LocalDateTime.now(), LocalDateTime.now().plusHours(2), new BigDecimal("-1.00"), 10);
        FlightRequest missing = new FlightRequest("AI-202", "Air India", "DEL", "BOM",
                LocalDateTime.now(), LocalDateTime.now().plusHours(2), null, 10);

        assertStatus(() -> flightService.create(negative), HttpStatus.BAD_REQUEST);
        assertStatus(() -> flightService.create(missing), HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("rejects a blank flight number or route")
    void rejectsBlankFields() {
        FlightRequest noNumber = new FlightRequest("  ", "Air India", "DEL", "BOM",
                LocalDateTime.now(), LocalDateTime.now().plusHours(2), BigDecimal.TEN, 10);
        FlightRequest noRoute = new FlightRequest("AI-202", "Air India", "DEL", null,
                LocalDateTime.now(), LocalDateTime.now().plusHours(2), BigDecimal.TEN, 10);

        assertStatus(() -> flightService.create(noNumber), HttpStatus.BAD_REQUEST);
        assertStatus(() -> flightService.create(noRoute), HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("findById returns the flight, or 404 for an unknown id")
    void findById() {
        Flight created = flightService.create(request(10));

        assertThat(flightService.findById(created.getId())).isSameAs(created);
        assertStatus(() -> flightService.findById(999L), HttpStatus.NOT_FOUND);
        assertStatus(() -> flightService.findById(null), HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("findAll lists flights in id order")
    void findAllIsOrdered() {
        flightService.create(request(10));
        flightService.create(request(20));

        assertThat(flightService.findAll())
                .extracting(Flight::getId)
                .containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("reserving seats decrements the inventory")
    void reserveDecrements() {
        Flight flight = flightService.create(request(10));

        flightService.reserveSeats(flight.getId(), 3);

        assertThat(flight.getAvailableSeats()).isEqualTo(7);
    }

    @Test
    @DisplayName("reserving more seats than remain is refused and changes nothing")
    void reserveRefusesOverbooking() {
        Flight flight = flightService.create(request(10));
        flightService.reserveSeats(flight.getId(), 8);

        assertStatus(() -> flightService.reserveSeats(flight.getId(), 3), HttpStatus.CONFLICT);

        assertThat(flight.getAvailableSeats())
                .as("a refused reservation must not consume seats")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("reserving on an unknown flight is a 404")
    void reserveUnknownFlight() {
        assertStatus(() -> flightService.reserveSeats(999L, 1), HttpStatus.NOT_FOUND);
        assertStatus(() -> flightService.reserveSeats(null, 1), HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("releasing seats puts them back")
    void releaseRestores() {
        Flight flight = flightService.create(request(10));
        flightService.reserveSeats(flight.getId(), 4);

        flightService.releaseSeats(flight.getId(), 4);

        assertThat(flight.getAvailableSeats()).isEqualTo(10);
    }

    @Test
    @DisplayName("releasing is clamped at totalSeats so seats cannot be invented")
    void releaseIsClamped() {
        Flight flight = flightService.create(request(10));
        flightService.reserveSeats(flight.getId(), 2);

        flightService.releaseSeats(flight.getId(), 2);
        flightService.releaseSeats(flight.getId(), 2);

        assertThat(flight.getAvailableSeats())
                .as("a repeated release must never exceed the aircraft capacity")
                .isEqualTo(10);
    }

    private FlightRequest request(int totalSeats) {
        return new FlightRequest("AI-202", "Air India", "DEL", "BOM",
                LocalDateTime.of(2026, 9, 15, 8, 30), LocalDateTime.of(2026, 9, 15, 10, 45),
                new BigDecimal("5400.00"), totalSeats);
    }

    private static void assertStatus(Runnable action, HttpStatusCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOf(ResponseStatusException.class)
                .extracting(thrown -> ((ResponseStatusException) thrown).getStatusCode())
                .isEqualTo(expected);
    }
}
