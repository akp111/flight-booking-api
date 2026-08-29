package com.flightbooking.controller;

import com.flightbooking.model.Flight;
import com.flightbooking.model.FlightRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("GET/POST /api/flights")
class FlightControllerTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    @DisplayName("creating a flight answers 201 with a Location header and a full aircraft")
    void createFlight() {
        ResponseEntity<Flight> response = rest.postForEntity("/api/flights", request(180), Flight.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Flight flight = response.getBody();
        assertThat(flight).isNotNull();
        assertThat(flight.getId()).isNotNull();
        assertThat(flight.getFlightNumber()).isEqualTo("AI-202");
        assertThat(flight.getTotalSeats()).isEqualTo(180);
        assertThat(flight.getAvailableSeats()).isEqualTo(180);
        assertThat(response.getHeaders().getLocation())
                .hasToString("/api/flights/" + flight.getId());
    }

    @Test
    @DisplayName("departure and arrival survive the JSON round trip as ISO-8601")
    void serialisesTimesAsIsoStrings() {
        ResponseEntity<String> response = rest.postForEntity("/api/flights", request(10), String.class);

        assertThat(response.getBody())
                .contains("\"departureTime\":\"2026-09-15T08:30:00\"")
                .contains("\"arrivalTime\":\"2026-09-15T10:45:00\"");
    }

    @Test
    @DisplayName("a flight with no seats is refused with 400 and a reason")
    void rejectsZeroSeats() {
        ResponseEntity<String> response = rest.postForEntity("/api/flights", request(0), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("totalSeats must be at least 1");
    }

    @Test
    @DisplayName("a negative price is refused with 400")
    void rejectsNegativePrice() {
        FlightRequest bad = new FlightRequest("AI-202", "Air India", "DEL", "BOM",
                LocalDateTime.of(2026, 9, 15, 8, 30), LocalDateTime.of(2026, 9, 15, 10, 45),
                new BigDecimal("-1.00"), 10);

        ResponseEntity<String> response = rest.postForEntity("/api/flights", bad, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("price must be zero or greater");
    }

    @Test
    @DisplayName("a created flight can be fetched by id")
    void getFlightById() {
        Long id = createFlight(60);

        ResponseEntity<Flight> response = rest.getForEntity("/api/flights/" + id, Flight.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getId()).isEqualTo(id);
        assertThat(response.getBody().getAvailableSeats()).isEqualTo(60);
    }

    @Test
    @DisplayName("an unknown flight id answers 404 with a reason")
    void unknownFlightIs404() {
        ResponseEntity<String> response = rest.getForEntity("/api/flights/999999", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("Flight 999999 not found");
    }

    @Test
    @DisplayName("the flight list includes what was just created")
    void listFlights() {
        Long id = createFlight(25);

        ResponseEntity<Flight[]> response = rest.getForEntity("/api/flights", Flight[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).extracting(Flight::getId).contains(id);
    }

    private Long createFlight(int totalSeats) {
        Flight created = rest.postForObject("/api/flights", request(totalSeats), Flight.class);
        assertThat(created).isNotNull();
        return created.getId();
    }

    private FlightRequest request(int totalSeats) {
        return new FlightRequest("AI-202", "Air India", "DEL", "BOM",
                LocalDateTime.of(2026, 9, 15, 8, 30), LocalDateTime.of(2026, 9, 15, 10, 45),
                new BigDecimal("5400.00"), totalSeats);
    }
}
