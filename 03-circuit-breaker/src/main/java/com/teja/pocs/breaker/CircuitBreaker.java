package com.teja.pocs.breaker;

/**
 * Goal 3: CLOSED -> OPEN after N failures in a row.
 * Goal 5: OPEN -> (wait) -> HALF_OPEN -> one trial request -> CLOSED (success) or OPEN again (failure).
 */
public class CircuitBreaker {

    enum State { CLOSED, OPEN, HALF_OPEN }

    private State state = State.CLOSED;
    private int consecutiveFailures = 0;
    private long openedAtMs = 0;                  // Goal 5: when we last went OPEN
    private final int failureThreshold;           // failures in a row that trip the breaker
    private final long openDurationMs;            // Goal 5: how long to stay OPEN before testing

    public CircuitBreaker(int threshold, long openDurationMs) {
        this.failureThreshold = threshold;
        this.openDurationMs = openDurationMs;
    }

    /**
     * "May I call the store?"
     *   CLOSED    -> true
     *   OPEN      -> false, unless the wait is over: then switch to HALF_OPEN and let ONE trial through
     *   HALF_OPEN -> false (the single trial is already in flight)
     */
    public synchronized boolean allowRequest() {
        if (state == State.CLOSED) return true;
        if (state == State.OPEN && System.currentTimeMillis() - openedAtMs >= openDurationMs) {
            state = State.HALF_OPEN;
            System.out.println(">>> HALF-OPEN: letting 1 trial request through");
            return true;                          // this caller is the trial
        }
        return false;
    }

    /** The store answered OK (200/404). */
    public synchronized void recordSuccess() {
        consecutiveFailures = 0;
        if (state == State.HALF_OPEN) {           // the trial worked -> the store is healthy again
            state = State.CLOSED;
            System.out.println(">>> CIRCUIT CLOSED: trial succeeded, normal traffic resumes");
        }
    }

    /** The store failed (429 after retries, or exception). */
    public synchronized void recordFailure() {
        consecutiveFailures++;
        if (state == State.HALF_OPEN) {           // the trial failed -> back to OPEN, wait again
            open(">>> trial FAILED: CIRCUIT OPEN again");
        } else if (state == State.CLOSED && consecutiveFailures >= failureThreshold) {
            open(">>> CIRCUIT OPEN after " + consecutiveFailures + " consecutive failures");
        }
    }

    private void open(String message) {           // helper: go OPEN and start the timer
        state = State.OPEN;
        openedAtMs = System.currentTimeMillis();
        System.out.println(message);
    }

    public synchronized State getState() {
        return state;
    }
}