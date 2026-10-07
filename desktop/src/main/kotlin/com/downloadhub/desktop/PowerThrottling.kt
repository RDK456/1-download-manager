package com.downloadhub.desktop

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * Keeps Windows from slowing the app down when its window is minimized or in the tray.
 *
 * Windows 11 "Power Throttling" (EcoQoS) runs a process with no window in front on the
 * efficiency cores at a lower clock, which is right for a chat app and wrong for a
 * download manager: downloads crawled as soon as the window went to the taskbar. A process
 * may opt out of execution-speed throttling for itself, which is what this does once at
 * start. A no-op anywhere this is not available.
 */
object PowerThrottling {
    @Suppress("FunctionName")
    private interface Kernel32 : Library {
        fun GetCurrentProcess(): Pointer
        fun SetProcessInformation(process: Pointer, infoClass: Int, info: Pointer, size: Int): Boolean
    }

    private const val PROCESS_POWER_THROTTLING = 4 // PROCESS_INFORMATION_CLASS.ProcessPowerThrottling
    private const val CURRENT_VERSION = 1
    private const val EXECUTION_SPEED = 1

    /** True when Windows accepted the opt-out. */
    fun optOut(): Boolean = runCatching {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return false
        val kernel = Native.load("kernel32", Kernel32::class.java)
        // PROCESS_POWER_THROTTLING_STATE { Version, ControlMask, StateMask }: control
        // execution speed, with the state bit clear, means "never throttle me".
        val state = Memory(12).apply {
            setInt(0, CURRENT_VERSION)
            setInt(4, EXECUTION_SPEED)
            setInt(8, 0)
        }
        kernel.SetProcessInformation(kernel.GetCurrentProcess(), PROCESS_POWER_THROTTLING, state, 12)
    }.getOrDefault(false)
}

/**
 * Keeps Windows from going to sleep while something is downloading, as IDM and FDM do: a
 * laptop left alone used to doze off mid-download. Each call resets the idle timer
 * (ES_SYSTEM_REQUIRED, without ES_CONTINUOUS), so the caller repeats it every so often
 * while the queue is busy and simply stops when it is not - nothing is left switched on.
 * The display may still turn off; only sleep is held back.
 */
object StayAwake {
    @Suppress("FunctionName")
    private interface Kernel32 : Library {
        fun SetThreadExecutionState(flags: Int): Int
    }

    private const val ES_SYSTEM_REQUIRED = 0x00000001

    private val kernel by lazy {
        runCatching {
            if (System.getProperty("os.name").orEmpty().startsWith("Windows")) Native.load("kernel32", Kernel32::class.java) else null
        }.getOrNull()
    }

    fun poke() {
        runCatching { kernel?.SetThreadExecutionState(ES_SYSTEM_REQUIRED) }
    }
}

/**
 * What to do once every download has finished - IDM's and FDM's "when done" switch.
 * One-shot: it returns to NOTHING after it has fired or been cancelled, so a shutdown
 * chosen tonight never surprises anyone tomorrow.
 */
object FinishAction {
    enum class Choice(val label: String) {
        NOTHING("Do nothing"),
        EXIT("Exit the app"),
        SLEEP("Sleep"),
        SHUTDOWN("Shut down")
    }

    var choice by androidx.compose.runtime.mutableStateOf(Choice.NOTHING)

    /** Carries the choice out. Exit is the caller's, since it owns the window. */
    fun perform(choice: Choice) {
        runCatching {
            when (choice) {
                Choice.SLEEP -> ProcessBuilder("rundll32.exe", "powrprof.dll,SetSuspendState", "0,1,0").start()
                Choice.SHUTDOWN -> ProcessBuilder("shutdown", "/s", "/t", "0").start()
                else -> Unit
            }
        }
    }
}
