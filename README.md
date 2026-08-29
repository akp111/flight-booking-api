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
│   ├── BookingController.java
│   └── ValidationExceptionHandler.java
├── service/             business rules + in-memory storage
│   ├── FlightService.java     flight catalogue and seat inventory
│   └── BookingService.java    bookings, keyed by booking reference
└── model/               data
    ├── Flight.java
    ├── Booking.java
    ├── BookingStatus.java     CONFIRMED | CANCELLED
    ├── FlightRequest.java     POST /api/v1/flights payload
    ├── BookingRequest.java    POST /api/v1/bookings payload
    └── ApiError.java          validation error response

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

Every route is versioned under `/api/v1`. The segment comes from `api.version` in
[application.properties](src/main/resources/application.properties), so moving the whole API to a
new version is a one-line change:

```properties
api.version=v1
```

### Flights

| Method | Path | Description |
| --- | --- | --- |
| `POST` | `/api/v1/flights` | Add a flight. Responds `201` with a `Location` header. |
| `GET` | `/api/v1/flights` | List all flights. |
| `GET` | `/api/v1/flights/{id}` | One flight, or `404`. |

### Bookings

| Method | Path | Description |
| --- | --- | --- |
| `POST` | `/api/v1/bookings` | Book seats. Responds `201`, or `409` if the flight is short on seats. |
| `DELETE` | `/api/v1/bookings/{reference}` | Cancel, release the seats, and return the updated booking. |

There are deliberately no endpoints for retrieving bookings. A booking reference is handed to the
caller once, in the `201` response to `POST /api/v1/bookings`, and is used only to cancel.

### Status codes

| Code | When |
| --- | --- |
| `400` | A request-body field fails validation — see below. |
| `404` | Unknown flight id or booking reference. |
| `409` | Not enough seats left, or cancelling an already-cancelled booking. |

Errors come back as JSON with a `message` explaining the reason:

```json
{"timestamp":"2026-08-30T01:44:33.592+05:30","status":409,"error":"Conflict","message":"Flight AI-202 has only 1 seat(s) available, requested 2","path":"/api/v1/bookings"}
```

### Validation

Request bodies are checked at the controller before any work happens, so a rejected request never
reaches the seat inventory.

| Payload | Rules |
| --- | --- |
| `POST /api/v1/flights` | `flightNumber`, `origin`, `destination` non-blank; `departureTime` and `arrivalTime` present; `price` present and zero or greater; `totalSeats` at least 1. `airline` is optional. |
| `POST /api/v1/bookings` | `flightId` present; `passengerName` non-blank; `passengerEmail` non-blank and a valid address; `seats` at least 1. |

Every violation is reported at once rather than only the first:

```json
{"timestamp":"2026-08-30T01:44:52.688+05:30","status":400,"error":"Bad Request","message":"passengerEmail must be a valid email address; passengerName is required; seats must be at least 1","path":"/api/v1/bookings"}
```

## Seat inventory

Booking decrements `availableSeats`; cancelling adds them back. Both run inside
`ConcurrentHashMap.compute`, which holds the map's lock for that flight while the check and the
update happen — so the "enough seats?" test and the decrement are a single atomic step and two
callers racing for the last seat cannot both win. A release is clamped at `totalSeats`, so a
repeated cancellation can never invent seats. If a booking fails after its seats were held, the
seats are returned rather than leaked.

`availableSeats` is `volatile`. The lock already rules out lost updates, but a reader outside it —
`GET /api/v1/flights` serialising a live `Flight` on a Tomcat thread while another thread books —
had no guarantee of seeing the newest committed count. It does now.

Verified end to end: 60 simultaneous HTTP bookings against a 5-seat flight produce exactly five
`201`s and fifty-five `409`s, leaving `availableSeats: 0`.

## Tests

```bash
./mvnw test
```

81 tests, no external services needed.

| Suite | Covers |
| --- | --- |
| `FlightServiceTest` | Inventory arithmetic in isolation: a new flight starts full, reservations decrement, a refused reservation changes nothing, releases are clamped at capacity. |
| `BookingServiceTest` | Booking and cancellation rules, plus four concurrency tests that hammer the seat inventory from 32 threads. |
| `FlightControllerTest` | The HTTP contract for flights against a real Tomcat: status codes, `Location` header, ISO-8601 dates, validation and error bodies. |
| `BookingControllerTest` | The HTTP contract for bookings: validation, cancellation, that no route returns booking data, and 60 concurrent bookings over real HTTP. |
| `FlightBookingApiApplicationTests` | Every layer is wired into the Spring context. |

The controller suites run on `RANDOM_PORT` with `TestRestTemplate`, so they exercise real HTTP,
real Jackson and real error handling rather than a mocked servlet layer.

### The overbooking tests

Overbooking is the rule most worth pinning down, so it is tested at every level and the tests were
checked against deliberately broken implementations:

- Deleting the "enough seats?" guard fails **37 tests** across all three layers.
- Keeping the guard but moving it *outside* the lock — the classic check-then-act race, which looks
  correct in single-threaded tests — is caught by `contentionOnTheLastSeatsNeverOversells`. That
  test puts 64 threads on a 2-seat flight, where every thread is fighting over the boundary. One
  round detects the race only about a third of the time, so it runs as a `@RepeatedTest(25)`.
  Reintroducing that race fails **6 tests**.

The other concurrency tests assert seat *conservation*: after cancellations race new bookings,
`seats held + seats available` must still equal the aircraft capacity — no seat invented by a
double cancellation, none lost by a failed booking.

## Walkthrough

```bash
# 1. Add a flight with 3 seats
curl -X POST http://localhost:8080/api/v1/flights \
  -H 'Content-Type: application/json' \
  -d '{"flightNumber":"AI-202","airline":"Air India","origin":"DEL","destination":"BOM",
       "departureTime":"2026-09-15T08:30:00","arrivalTime":"2026-09-15T10:45:00",
       "price":5400.00,"totalSeats":3}'

# 2. Book 2 of them — note the bookingReference in the response
curl -X POST http://localhost:8080/api/v1/bookings \
  -H 'Content-Type: application/json' \
  -d '{"flightId":1,"passengerName":"Ashis Pradhan",
       "passengerEmail":"ashis@example.com","seats":2}'

# 3. The flight now shows availableSeats: 1
curl http://localhost:8080/api/v1/flights/1

# 4. Asking for 2 more is rejected with 409
curl -X POST http://localhost:8080/api/v1/bookings \
  -H 'Content-Type: application/json' \
  -d '{"flightId":1,"passengerName":"Riya Sen",
       "passengerEmail":"riya@example.com","seats":2}'

# 5. Cancel it — replace FB-XXXXXX with the bookingReference from step 2
curl -X DELETE http://localhost:8080/api/v1/bookings/FB-XXXXXX
curl http://localhost:8080/api/v1/flights/1        # availableSeats: 3 again
```

## Requirements

- Java 21 or later (`java -version`)
- No Maven install needed — use the bundled `./mvnw`
