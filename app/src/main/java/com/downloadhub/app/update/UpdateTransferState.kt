package com.downloadhub.app.update

import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the update transfer is doing, shared between the service and the UI. */
sealed interface TransferState {
    data object Idle : TransferState
    data class Running(val version: String, val name: String, val downloadedBytes: Long, val totalBytes: Long) : TransferState {
        val percent: Int
            get() = if (totalBytes > 0) {
                ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
            } else {
                0
            }
    }

    data class Done(val version: String, val name: String, val apk: File) : TransferState
    data class Failed(val message: String) : TransferState
}

/**
 * Process-wide handoff between [UpdateService] and the update UI.
 *
 * The service can finish while no Activity exists, so the result is parked here
 * and picked up when the UI next appears. That is what lets a backgrounded update
 * complete and still be installable instead of being lost with the Activity.
 */
object UpdateTransferState {
    private val _state = MutableStateFlow<TransferState>(TransferState.Idle)
    val state: StateFlow<TransferState> = _state.asStateFlow()

    fun start(version: String, name: String, totalBytes: Long) {
        _state.value = TransferState.Running(version, name, 0L, totalBytes)
    }

    fun progress(downloaded: Long, total: Long) {
        val current = _state.value as? TransferState.Running ?: return
        _state.value = current.copy(
            downloadedBytes = downloaded,
            totalBytes = if (total > 0) total else current.totalBytes
        )
    }

    fun complete(version: String, name: String, apk: File) {
        _state.value = TransferState.Done(version, name, apk)
    }

    fun fail(message: String) {
        _state.value = TransferState.Failed(message)
    }

    /** The UI has taken note of the outcome and is showing it. */
    fun acknowledge() {
        if (_state.value is TransferState.Running) return
        _state.value = TransferState.Idle
    }
}
