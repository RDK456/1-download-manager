package com.downloadhub.app.ui.expressive.model

/**
 * The badge on a torrent search result, from how healthy the torrent is. The source's own
 * word is kept: a site that publishes no counts gets the plain disc ("unknown"), never the
 * warning shape, because "no counts" is not "nobody is sharing it".
 */
fun searchHealthShape(reportsHealth: Boolean, seeders: Int): StateShapeKind = when {
    !reportsHealth -> StateShapeKind.CIRCLE
    seeders > 0 -> StateShapeKind.COOKIE
    else -> StateShapeKind.PENTAGON
}

/** The line under a result's name: size, health and where it came from, with nothing invented. */
fun searchSummary(sizeText: String, reportsHealth: Boolean, seeders: Int, leechers: Int, source: String): List<String> = buildList {
    add(sizeText)
    add(if (reportsHealth) "$seeders ${if (seeders == 1) "seed" else "seeds"} · $leechers ${if (leechers == 1) "peer" else "peers"}" else "seeds and peers unknown")
    add(source)
}
