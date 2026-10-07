package com.shilapi.xcertplay

import android.util.Log

/**
 * One gate for everything that would otherwise spam logcat on the head unit.
 *
 * The shipped build was writing every iAP2 frame, every periodic wireless snapshot and every
 * vendor probe straight to logcat at INFO level — tens of megabytes per session. Only [w] and
 * [e] stay unconditional; [d] and [i] are dropped unless verbose logging is on.
 *
 * Verbose logging is on for debuggable builds, and for release builds when the user turns on the
 * existing "debug logs" switch in the projection screen (owner bug reports need it). The flag is
 * pushed from `common`, which owns the preference — `shared` cannot read it directly.
 *
 * Note this only governs logcat. In-app diagnostics are a product feature (the user can export a
 * report), so callers keep feeding their own UI listeners regardless of this gate.
 */
object Diagnostics {
    @Volatile
    var verboseToLogcat: Boolean = false

    /** Connection milestones and stage changes; dropped unless verbose logging is on. */
    fun i(tag: String, message: String) {
        if (verboseToLogcat) Log.i(tag, message)
    }

    /** Per-frame protocol traces and periodic snapshots; dropped unless verbose logging is on. */
    fun d(tag: String, message: String) {
        if (verboseToLogcat) Log.d(tag, message)
    }

    /** Recoverable problems. Kept: these are what makes a field report useful. */
    fun w(tag: String, message: String, error: Throwable? = null) {
        if (error == null) Log.w(tag, message) else Log.w(tag, message, error)
    }

    /** Failures. Always kept. */
    fun e(tag: String, message: String, error: Throwable? = null) {
        if (error == null) Log.e(tag, message) else Log.e(tag, message, error)
    }
}
