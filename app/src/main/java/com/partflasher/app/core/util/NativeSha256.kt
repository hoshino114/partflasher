package com.partflasher.app.core.util

import java.io.File

/**
 * Optional arm64/x86_64 JNI implementation of streaming SHA-256.
 *
 * The library is packaged for arm64-v8a in release builds and additionally for
 * x86_64 in debug/emulator builds. When it is absent or fails, callers must use
 * their pure-JVM fallback; this object never throws.
 */
object NativeSha256 {
    private val libraryLoaded: Boolean by lazy {
        runCatching {
            System.loadLibrary("partflasher")
            true
        }.getOrDefault(false)
    }

    private external fun nativeSha256File(path: String): String?

    private external fun nativeVersion(): String?

    /** Returns the lowercase SHA-256 of [file], or null if native hashing is unavailable. */
    fun sha256OrNull(file: File): String? {
        if (!libraryLoaded || !file.isFile) return null
        return runCatching { nativeSha256File(file.absolutePath) }.getOrNull()
            ?.takeIf { it.length == 64 }
    }

    /** Human-readable backend description for the diagnostics/guide screen. */
    fun backendName(): String = if (libraryLoaded) {
        runCatching { nativeVersion() }.getOrNull() ?: "native"
    } else {
        "JVM"
    }

    val isAvailable: Boolean get() = libraryLoaded
}
