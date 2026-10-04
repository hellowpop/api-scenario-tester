package io.github.apiscenariotester.script;

/** Cooperative cancellation for a single sequential run. */
public final class ExecutionControl {
    private boolean stopped;
    private String reason = "";
    public void stop(String reason) { this.stopped = true; this.reason = reason == null ? "" : reason; }
    public boolean isStopped() { return stopped; }
    public String getReason() { return reason; }
}
