package android.os

/** Host monotonic clock; Android deep sleep is outside this offline harness. */
object SystemClock {
    @JvmStatic fun elapsedRealtime(): Long = System.nanoTime() / 1_000_000L
}
