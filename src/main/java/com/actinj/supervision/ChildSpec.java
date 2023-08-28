package com.actinj.supervision;

import java.time.temporal.TemporalAmount;

public record ChildSpec(String id, Runnable start, Restart restart, TemporalAmount shutdown) {
}
