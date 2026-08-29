package com.flightbooking.service;

import com.flightbooking.model.Flight;
import com.flightbooking.model.FlightRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Owns the flight catalogue and its seat inventory, held in memory.
 */
@Service
public class FlightService {

    private final Map<Long, Flight> flights = new ConcurrentHashMap<>();
    private final AtomicLong idSequence = new AtomicLong();

    public Flight create(FlightRequest request) {
        if (request.totalSeats() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "totalSeats must be at least 1");
        }
        if (request.price() == null || request.price().signum() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "price must be zero or greater");
        }
        if (isBlank(request.flightNumber())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "flightNumber is required");
        }
        if (isBlank(request.origin()) || isBlank(request.destination())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "origin and destination are required");
        }

        Flight flight = new Flight(
                idSequence.incrementAndGet(),
                request.flightNumber(),
                request.airline(),
                request.origin(),
                request.destination(),
                request.departureTime(),
                request.arrivalTime(),
                request.price(),
                request.totalSeats(),
                request.totalSeats());
        flights.put(flight.getId(), flight);
        return flight;
    }

    public List<Flight> findAll() {
        return flights.values().stream()
                .sorted(Comparator.comparing(Flight::getId))
                .toList();
    }

    public Flight findById(Long id) {
        Flight flight = id == null ? null : flights.get(id);
        if (flight == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Flight " + id + " not found");
        }
        return flight;
    }

    /**
     * Takes {@code seats} off this flight's inventory, or fails with 409 if there aren't enough.
     *
     * <p>The check and the decrement run inside {@code compute}, which holds the map's lock for
     * this key until the remapping function returns. That makes the read-modify-write atomic, so
     * two callers racing for the last seat cannot both succeed. Throwing from inside the function
     * leaves the mapping untouched, so a rejected reservation changes nothing.
     */
    public void reserveSeats(Long flightId, int seats) {
        flights.compute(requireId(flightId), (id, flight) -> {
            if (flight == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Flight " + id + " not found");
            }
            if (flight.getAvailableSeats() < seats) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Flight " + flight.getFlightNumber() + " has only " + flight.getAvailableSeats()
                                + " seat(s) available, requested " + seats);
            }
            flight.setAvailableSeats(flight.getAvailableSeats() - seats);
            return flight;
        });
    }

    /**
     * Returns {@code seats} to this flight's inventory, clamped at {@code totalSeats} so a
     * double release can never inflate the aircraft.
     */
    public void releaseSeats(Long flightId, int seats) {
        flights.compute(requireId(flightId), (id, flight) -> {
            if (flight == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Flight " + id + " not found");
            }
            flight.setAvailableSeats(Math.min(flight.getTotalSeats(), flight.getAvailableSeats() + seats));
            return flight;
        });
    }

    private Long requireId(Long flightId) {
        if (flightId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "flightId is required");
        }
        return flightId;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
