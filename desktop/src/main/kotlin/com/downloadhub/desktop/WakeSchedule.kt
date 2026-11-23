package com.downloadhub.desktop

/**
 * Wakes the computer for a queue's start time, through a Windows scheduled task.
 *
 * One task per queue, named after it. A queue with its schedule off or "wake" unticked has
 * its task removed. The task starts the app two minutes early, so a computer that was
 * asleep is up and online when the queue begins.
 */
object WakeSchedule {
    private fun taskName(id: String) = "1DownloadManager-Wake-" + id.filter { it.isLetterOrDigit() }

    fun sync(queues: List<QueueConfig>) {
        if (!System.getProperty("os.name").lowercase().contains("win")) return
        val exe = FileAssociations.launcherExe()?.absolutePath
        queues.forEach { q ->
            val name = taskName(q.id)
            val script = if (q.scheduleEnabled && q.wake && exe != null) {
                val minute = ((q.startMinute - 2) % (24 * 60) + 24 * 60) % (24 * 60)
                val days = q.days.sorted().joinToString(",") {
                    listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")[(it - 1).coerceIn(0, 6)]
                }
                val at = "%02d:%02d".format(minute / 60, minute % 60)
                "\$a = New-ScheduledTaskAction -Execute '$exe'; " +
                    "\$t = New-ScheduledTaskTrigger -Weekly -DaysOfWeek $days -At $at; " +
                    "\$s = New-ScheduledTaskSettingsSet -WakeToRun -StartWhenAvailable; " +
                    "Register-ScheduledTask -TaskName '$name' -Action \$a -Trigger \$t -Settings \$s -Force | Out-Null"
            } else {
                "Unregister-ScheduledTask -TaskName '$name' -Confirm:\$false -ErrorAction SilentlyContinue"
            }
            runCatching {
                ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script)
                    .redirectErrorStream(true).start().waitFor(20, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
    }
}
