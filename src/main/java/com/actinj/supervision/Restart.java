package com.actinj.supervision;

public enum Restart {
    PERMANENT, // Will always be restarted
    TEMPORARY, // Will never be restarted
    TRANSIENT; // Will restart if stopped by an uncaught exception
}
