package com.flightbooking;

import com.flightbooking.controller.BookingController;
import com.flightbooking.controller.FlightController;
import com.flightbooking.service.BookingService;
import com.flightbooking.service.FlightService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@DisplayName("application wiring")
class FlightBookingApiApplicationTests {

    @Autowired
    private FlightController flightController;

    @Autowired
    private BookingController bookingController;

    @Autowired
    private FlightService flightService;

    @Autowired
    private BookingService bookingService;

    @Test
    @DisplayName("every layer is wired into the context")
    void contextLoads() {
        assertThat(flightController).isNotNull();
        assertThat(bookingController).isNotNull();
        assertThat(flightService).isNotNull();
        assertThat(bookingService).isNotNull();
    }
}
