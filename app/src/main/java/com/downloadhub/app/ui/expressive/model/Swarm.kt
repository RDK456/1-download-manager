package com.downloadhub.app.ui.expressive.model

/** Who a torrent is connected to right now: seeds (have everything) and other peers. */
data class Swarm(val seeds: Int, val peers: Int) {
    /** Connected to nobody at all. */
    val isEmpty: Boolean get() = seeds <= 0 && peers <= 0
}

private fun plural(n: Int, one: String, many: String) = if (n == 1) "$n $one" else "$n $many"

/**
 * The line shown on a torrent's row, or null when there is nothing worth saying. While
 * downloading it says who is sending; once finished and still sharing, who is connected.
 */
fun swarmText(swarm: Swarm?, finished: Boolean): String? {
    if (swarm == null) return null
    return if (finished) {
        if (swarm.peers <= 0) null else "Sharing with ${plural(swarm.peers, "peer", "peers")}"
    } else {
        "${plural(swarm.seeds, "seed", "seeds")} · ${plural(swarm.peers, "peer", "peers")}"
    }
}
