# ActInJ 🎭

Java 21 brings Virtual Threads which with the potential to completely change how we do concurrency in Java. No more
thread-pools, and no more callbacks to avoid blocking one of those scare operating system threads. Instead, we are now
free to spawn a new Virtual Thread wherever we feel it could be useful. These threads can consist of straightforward,
easy to read and easy to debug blocking calls. But how do we maintain order in this potentially huge number of Virtual
Threads? It might pay of to look at other languages, that have been working with Virtual Threads for decades, to see
how applications are structured there.

The goal of this repo is to experiment in Java 21, with concepts from Erlang and Elixir. Beginning with [Supervision
Trees](https://adoptingerlang.org/docs/development/supervision_trees/).

## Supervisor
Supervisors in Erlang, rely heavily on gen_server. Gen_server is an interesting concept on itself, that may later be
the subject of some experimenting in this repo, but I wanted my supervisor to be easy to integrate in existing Java
applications. Therefore, I try
to write a supervisor that can work similarly to a thread-pool, with existing runnables (the only requirement being
that they gracefully deal with InterruptedExceptions).

The supervisor in this repo has several goals:
* Provide an interface trough which a group of Virtual Threads can be managed (started, stopped, queried)
* Monitor its child threads, and automatically restart them should they crash
* Make sure all child threads stop when the supervisor stops (intentionally or unintentionally)
* A supervisor can be the child of another supervisor (Supervision Trees)

No documentation yet on how to use this thing. It's very early days and the API will certainly still change.

### Supervisor Caveats
There are of course some important differences between Virtual Threads in Java and Erlang. Erlang threads don't share
memory (and are therefore called processes, not threads). A crash in one Erlang process, can not affect the state of another.
Moreover, Erlang's gen_server makes state modifications atomic. If a process crashes while changing the state, you still
have the full state from before the change, which you can use to restart the thread with. Therefore, restarting Erlang
processes makes sense to attempt keeping a system up. Restarting a Java thread only makes sense if it is written in such
a way that the crashing thread did not leave behind inconsistent data for the new thread to use, or for other parts of
the application.

## License
*ActInJ* is published under the [BSD 3-Clause License](LICENSE "BSD 3-Clause License")
