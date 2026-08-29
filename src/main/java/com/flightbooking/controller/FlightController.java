package com.flightbooking.controller;

import com.flightbooking.model.Flight;
import com.flightbooking.model.FlightRequest;
import com.flightbooking.service.FlightService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/flights")
public class FlightController {

    private final FlightService flightService;

    public FlightController(FlightService flightService) {
        this.flightService = flightService;
    }

    @PostMapping
    public ResponseEntity<Flight> create(@RequestBody FlightRequest request) {
        Flight flight = flightService.create(request);
        return ResponseEntity.created(URI.create("/api/flights/" + flight.getId())).body(flight);
    }

    @GetMapping
    public List<Flight> findAll() {
        return flightService.findAll();
    }

    @GetMapping("/{id}")
    public Flight findById(@PathVariable Long id) {
        return flightService.findById(id);
    }
}
