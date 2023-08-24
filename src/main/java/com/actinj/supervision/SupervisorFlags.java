package com.actinj.supervision;

import java.time.temporal.TemporalAmount;

public record SupervisorFlags(Strategy strategy, int intensity, TemporalAmount period) {
}
