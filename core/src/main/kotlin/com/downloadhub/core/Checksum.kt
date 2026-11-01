package com.downloadhub.core

import java.io.File
import java.security.MessageDigest

/** Checks a finished file against a hash the site published. */
object Checksum {
    /** The algorithm a hex string belongs to, from its length; null when it is none of them. */
    fun algorithmFor(expected: String): String? = when (clean(expected).length) {
        32 -> "MD5"
        40 -> "SHA-1"
        64 -> "SHA-256"
        128 -> "SHA-512"
        else -> null
    }

    fun clean(text: String): String = text.trim().lowercase().filter { it in '0'..'9' || it in 'a'..'f' }

    fun hex(file: File, algorithm: String): String {
        val digest = MessageDigest.getInstance(algorithm)
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** True when [file] hashes to [expected]; null when the expected text is not a known hash. */
    fun matches(file: File, expected: String): Boolean? {
        val algorithm = algorithmFor(expected) ?: return null
        return hex(file, algorithm) == clean(expected)
    }
}
