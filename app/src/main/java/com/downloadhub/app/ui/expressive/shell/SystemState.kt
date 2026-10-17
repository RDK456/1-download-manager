package com.downloadhub.app.ui.expressive.shell

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.app.NotificationManagerCompat

/**
 * Whether the phone has a usable connection right now, kept live. The Downloads list uses
 * it to say "Waiting for network" instead of showing rows that look stuck.
 */
@Composable
fun rememberOnline(): State<Boolean> {
    val context = LocalContext.current
    val manager = remember { context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager }
    val online = remember { mutableStateOf(manager?.activeNetwork != null) }
    DisposableEffect(manager) {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { online.value = true }
            override fun onLost(network: Network) { online.value = manager?.activeNetwork != null }
        }
        runCatching { manager?.registerDefaultNetworkCallback(callback) }
        onDispose { runCatching { manager?.unregisterNetworkCallback(callback) } }
    }
    return online
}

/**
 * A value that is read again every time the app comes back to the front. Permissions and
 * battery settings are changed in system screens, so they must be re-read on return or the
 * notice stays up after the user has fixed what it asked for.
 */
@Composable
fun <T> rememberOnResume(read: () -> T): State<T> {
    val state = remember { mutableStateOf(read()) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) state.value = read() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return state
}

@Composable
fun rememberNotificationsEnabled(): State<Boolean> {
    val context = LocalContext.current
    return rememberOnResume { NotificationManagerCompat.from(context).areNotificationsEnabled() }
}
