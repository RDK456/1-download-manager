package com.downloadhub.desktop

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer

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
