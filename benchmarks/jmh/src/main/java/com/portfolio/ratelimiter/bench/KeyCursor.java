package com.portfolio.ratelimiter.bench;

import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.ThreadParams;

/** Per-thread position in the shared key pool; threads start far apart to avoid lockstep. */
@State(Scope.Thread)
public class KeyCursor {

    int next;

    @Setup(Level.Trial)
    public void init(ThreadParams threads) {
        next = threads.getThreadIndex() * 104_729;
    }
}
