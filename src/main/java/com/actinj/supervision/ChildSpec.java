package com.actinj.supervision;

public record ChildSpec(String id, Runnable start, Restart restart) {
}
