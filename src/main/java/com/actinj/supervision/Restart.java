package com.actinj.supervision;

public enum Restart {
    PERMANENT, // Will always be restarted
    TEMPORARY, // Will never be restarted
    TRANSIENT; // Will restart if stopped while the running flag was still true
}
