package com.actinj.supervision;

public enum Strategy {
    ONE_FOR_ONE, // If one child process terminates and is to be restarted, only that child process is affected. This is
                 // the default restart strategy.
    ONE_FOR_ALL, // If one child process terminates and is to be restarted, all other child processes are terminated and
                 // then all child processes are restarted.
    REST_FOR_ONE; // If one child process terminates and is to be restarted, the 'rest' of the child processes (that is,
                  // the child processes after the terminated child process in the start order) are terminated. Then the
                  // terminated child process and all child processes after it are restarted.
}
