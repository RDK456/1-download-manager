package com.downloadhub.desktop

/**
 * Starting the app when Windows starts, through the per-user `Run` key - the same place
 * Task Manager's Startup tab reads, so the user can see it and turn it off there too.
 *
 * The registry is the only record: there is no setting to drift out of step with it. Only
 * the installed launcher can be registered, never a development JVM (see
 * [FileAssociations.launcherExe]).
 */
object AutoStart {
    private const val RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private const val VALUE_NAME = "1DownloadManager"

    /** Passed by the Run entry, so a start at sign-in opens quietly instead of in the user's face. */
    const val SWITCH = "--minimized"

    /** False when not running as the installed app, so the option is shown disabled. */
    fun available(): Boolean = FileAssociations.launcherExe() != null

    fun isEnabled(): Boolean = FileAssociations.regQuery(RUN_KEY, VALUE_NAME) != null

    /** Turns it on or off; true when the registry now says what was asked. */
    fun set(enabled: Boolean): Boolean {
        if (!enabled) return !isEnabled() || FileAssociations.regDeleteValue(RUN_KEY, VALUE_NAME)
        val exe = FileAssociations.launcherExe() ?: return false
        return FileAssociations.regSet(RUN_KEY, VALUE_NAME, "\"${exe.absolutePath}\" $SWITCH")
    }

    /**
     * Points an existing entry at this copy. After an update or a move the stored path can
     * be stale, and a Run entry to a missing file just fails silently at every sign-in.
     */
    fun refresh() {
        if (isEnabled()) set(true)
    }
}
