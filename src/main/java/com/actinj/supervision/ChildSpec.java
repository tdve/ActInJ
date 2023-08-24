package com.actinj.supervision;

import java.time.temporal.TemporalAmount;

// TODO: shutdown is probably not useful here. We can't force a thread to stop. We can nly nicely ask by sending it an interrupt
public record ChildSpec(String id, Runnable start, Restart restart, TemporalAmount shutdown) {
}
