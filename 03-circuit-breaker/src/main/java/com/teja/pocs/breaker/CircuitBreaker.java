package com.teja.pocs.breaker;

/** Goal 3: CLOSED -> OPEN after N failures in a row. (HALF_OPEN comes in Goal 5.) */
public class CircuitBreaker {

    enum State { CLOSED, OPEN, HALF_OPEN }

    private State state = State.CLOSED;
    private int consecutiveFailures = 0;
    private final int failureThreshold;           // set once, in the constructor

    public CircuitBreaker(int threshold) {
        this.failureThreshold = threshold;
    }

    /** "May I call the store?"  CLOSED -> true, OPEN -> false. */
    public synchronized boolean allowRequest() {
        return state == State.CLOSED;
    }

    /** The store answered OK (200/404): the dependency is healthy -> the failure streak is broken. */
    public synchronized void recordSuccess() {
        consecutiveFailures = 0;
    }

    /** The store failed (429 after retries, or exception). Trip to OPEN at the threshold. */
    public synchronized void recordFailure() {
        consecutiveFailures++;
        if (state == State.CLOSED && consecutiveFailures >= failureThreshold) {
            state = State.OPEN;
            System.out.println(">>> CIRCUIT OPEN after " + consecutiveFailures + " consecutive failures");
        }
    }

    public synchronized State getState() {
        return state;
    }
}