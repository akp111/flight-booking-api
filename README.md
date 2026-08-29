# Flight Ticket Booking REST API

A runnable flight booking API built with Java 21 and Spring Boot 3.5.16, organised as a simple
clean architecture: **Controller → Service → Model**.

Flights and bookings are held in memory (`ConcurrentHashMap` inside the services), so there is no
database to install and no configuration to fill in — clone, run, book. State resets on restart.

## Quickstart

```bash
./mvnw spring-boot:run
```

The API listens on <http://localhost:8080>. To build a self-contained jar instead:

```bash
./mvnw clean package
java -jar target/flight-booking-api-1.0.0.jar
```

Maven itself does not need to be installed — `./mvnw` downloads it on first run.

## Architecture

```
src/main/java/com/flightbooking/
├── FlightBookingApiApplication.java
├── controller/          HTTP layer — maps requests to services, holds no logic
│   ├── FlightController.java
│   └── BookingController.java
├── service/             business rules + in-memory storage
│   ├── FlightService.java     flight catalogue and seat inventory
│   └── BookingService.java    bookings, keyed by booking reference
└── model/               data
    ├── Flight.java
    ├── Booking.java
    ├── BookingStatus.java     CONFIRMED | CANCELLED
    ├── FlightRequest.java     POST /api/flights payload
    └── BookingRequest.java    POST /api/bookings payload

src/test/java/com/flightbooking/
├── FlightBookingApiApplicationTests.java   context wiring
├── controller/          HTTP contract, over real Tomcat
│   ├── FlightControllerTest.java
│   └── BookingControllerTest.java
└── service/             business rules + concurrency
    ├── FlightServiceTest.java
    └── BookingServiceTest.java
```

Each layer depends only on the one below it: controllers call services, services own models.
Request payloads are separate from the entities so callers cannot set server-owned fields such as
`id`, `availableSeats`, `totalPrice` or `status`.

## Endpoints

### Flights

| Method | Path | Description |
| --- | --- | --- |
| `POST` | `/api/flights` | Add a flight. Responds `201` with a `Location` header. |
| `GET` | `/api/flights` | List all flights. |
| `GET` | `/api/flights/{id}` | One flight, or `404`. |

### Bookings

| Method | Path | Description |
| --- | --- | --- |
| `POST` | `/api/bookings` | Book seats. Responds `201`, or `409` if the flight is short on seats. |
| `DELETE` | `/api/bookings/{reference}` | Cancel, release the seats, and return the updated booking. |

There are deliberately no endpoints for retrieving bookings. A booking reference is handed to the
caller once, in the `201` response to `POST /api/bookings`, and is used only to cancel.

### Status codes

| Code | When |
| --- | --- |
| `400` | `seats` below 1, missing passenger name or email, `totalSeats` below 1, negative price. |
| `404` | Unknown flight id or booking reference. |
| `409` | Not enough seats left, or cancelling an already-cancelled booking. |

Errors come back as JSON with a `message` explaining the reason:

```json
{"status":409,"error":"Conflict","message":"Flight AI-202 has only 1 seat(s) available, requested 2","path":"/api/bookings"}
```

## Seat inventory

Booking decrements `availableSeats`; cancelling adds them back. Both run inside
`ConcurrentHashMap.compute`, which holds the map's lock for that flight while the check and the
update happen — so the "enough seats?" test and the decrement are a single atomic step and two
callers racing for the last seat cannot both win. A release is clamped at `totalSeats`, so a
repeated cancellation can never invent seats. If a booking fails after its seats were held, the
seats are returned rather than leaked.

Verified against a live server: 10 concurrent requests for a 1-seat flight produced exactly one
`201` and nine `409`s, leaving `availableSeats: 0`.

## Tests

```bash
./mvnw test
```

72 tests, no external services needed.

| Suite | Covers |
| --- | --- |
| `FlightServiceTest` | Inventory arithmetic in isolation: a new flight starts full, reservations decrement, a refused reservation changes nothing, releases are clamped at capacity. |
| `BookingServiceTest` | Booking and cancellation rules, plus four concurrency tests that hammer the seat inventory from 32 threads. |
| `FlightControllerTest` | The HTTP contract for flights against a real Tomcat: status codes, `Location` header, ISO-8601 dates, error bodies. |
| `BookingControllerTest` | The HTTP contract for bookings, including that no route returns booking data. |
| `FlightBookingApiApplicationTests` | Every layer is wired into the Spring context. |

The controller suites run on `RANDOM_PORT` with `TestRestTemplate`, so they exercise real HTTP,
real Jackson and real error handling rather than a mocked servlet layer.

### The overbooking tests

Overbooking is the rule most worth pinning down, so it is tested at every level and the tests were
checked against deliberately broken implementations:

- Deleting the "enough seats?" guard fails **11 tests** across all three layers.
- Keeping the guard but moving it *outside* the lock — the classic check-then-act race, which looks
  correct in single-threaded tests — is caught by `contentionOnTheLastSeatsNeverOversells`. That
  test puts 64 threads on a 2-seat flight, where every thread is fighting over the boundary. One
  round detects the race only about a third of the time, so it runs as a `@RepeatedTest(25)`.

The other concurrency tests assert seat *conservation*: after cancellations race new bookings,
`seats held + seats available` must still equal the aircraft capacity — no seat invented by a
double cancellation, none lost by a failed booking.

## Walkthrough

```bash
# 1. Add a flight with 3 seats
curl -X POST http://localhost:8080/api/flights \
  -H 'Content-Type: application/json' \
  -d '{"flightNumber":"AI-202","airline":"Air India","origin":"DEL","destination":"BOM",
       "departureTime":"2026-09-15T08:30:00","arrivalTime":"2026-09-15T10:45:00",
       "price":5400.00,"totalSeats":3}'

# 2. Book 2 of them — note the bookingReference in the response
curl -X POST http://localhost:8080/api/bookings \
  -H 'Content-Type: application/json' \
  -d '{"flightId":1,"passengerName":"Ashis Pradhan",
       "passengerEmail":"ashis@example.com","seats":2}'

# 3. The flight now shows availableSeats: 1
curl http://localhost:8080/api/flights/1

# 4. Asking for 2 more is rejected with 409
curl -X POST http://localhost:8080/api/bookings \
  -H 'Content-Type: application/json' \
  -d '{"flightId":1,"passengerName":"Riya Sen",
       "passengerEmail":"riya@example.com","seats":2}'

# 5. Cancel it using the reference from step 2 — status becomes CANCELLED, seats go back
curl -X DELETE http://localhost:8080/api/bookings/FB-XXXXXX
curl http://localhost:8080/api/flights/1        # availableSeats: 3 again
```

## Requirements

- Java 21 or later (`java -version`)
- No Maven install needed — use the bundled `./mvnw`
