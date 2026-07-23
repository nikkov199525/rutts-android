package ru.hlebushek.RuTTS.engine

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

internal class SynthesisStartGate {
    private val lastStopNanos = AtomicLong(0)

    fun markStop() {
        lastStopNanos.set(System.nanoTime())
    }

    fun awaitStart(isActive: () -> Boolean): Boolean {
        val stoppedAt = lastStopNanos.get()
        if (stoppedAt == 0L) return isActive()
        while (isActive()) {
            val remaining = stoppedAt + COALESCE_NANOS - System.nanoTime()
            if (remaining <= 0L) return true
            LockSupport.parkNanos(remaining)
        }
        return false
    }

    private companion object {
        const val COALESCE_NANOS = 2_000_000L
    }
}
