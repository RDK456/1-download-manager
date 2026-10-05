package com.downloadhub.app.download

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.downloadhub.app.AppContainer
import com.downloadhub.app.DownloadHubApplication
import com.downloadhub.app.data.model.DownloadCreateRequest
import com.downloadhub.core.RssItem
import com.downloadhub.core.RssParser
import com.downloadhub.core.RssRule
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/** One subscribed feed. */
data class RssFeed(val url: String, val name: String)

/** Feeds and rules as DataStore strings: a line each, tab-separated fields. */
object RssCodec {
    fun encodeFeeds(feeds: List<RssFeed>): String =
        feeds.joinToString("\n") { "${it.url}\t${it.name.replace('\t', ' ')}" }

    fun decodeFeeds(text: String?): List<RssFeed> = text.orEmpty().lines().mapNotNull { line ->
        val f = line.split('\t')
        f.getOrNull(0)?.takeIf { it.startsWith("http") }?.let { RssFeed(it, f.getOrNull(1).orEmpty()) }
    }

    fun encodeRules(rules: List<RssRule>): String = rules.joinToString("\n") { r ->
        listOf(r.name, r.mustContain, r.mustNotContain, if (r.useRegex) "1" else "0", if (r.enabled) "1" else "0")
            .joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ') }
    }

    fun decodeRules(text: String?): List<RssRule> = text.orEmpty().lines().mapNotNull { line ->
        val f = line.split('\t')
        if (f.size < 5 || f[1].isBlank()) return@mapNotNull null
        RssRule(f[0], f[1], f[2], useRegex = f[3] == "1", enabled = f[4] == "1")
    }
}

/**
 * Reads the feeds and runs the auto-download rules over what is new. On a feed's first
 * read everything is only marked seen, so a new rule does not download every back episode.
 */
object RssSync {
    private fun seenFile(context: Context) = File(context.filesDir, "rss-seen.txt")

    suspend fun refresh(context: Context, container: AppContainer): Map<String, List<RssItem>> {
        val settings = container.settings
        val feeds = settings.rssFeeds()
        val rules = settings.rssRules()
        val proxy = settings.currentAdvanced().proxy.toProxy()
        val seenFile = seenFile(context)
        val seen = runCatching { seenFile.readLines().toMutableSet() }.getOrDefault(mutableSetOf())
        val newlySeen = mutableListOf<String>()
        val result = feeds.associate { feed ->
            val items = runCatching { RssParser.parse(fetch(feed.url, proxy)) }.getOrDefault(emptyList())
            val firstRead = seen.none { it.startsWith(feed.url + "|") }
            items.forEach { item ->
                val key = feed.url + "|" + item.guid
                if (seen.add(key)) {
                    newlySeen += key
                    if (!firstRead && rules.any { it.matches(item.title) }) download(context, container, item)
                }
            }
            feed.url to items
        }
        if (newlySeen.isNotEmpty()) runCatching { seenFile.appendText(newlySeen.joinToString("\n", postfix = "\n")) }
        return result
    }

    /** Queues an article the same way a pasted link is queued. */
    suspend fun download(context: Context, container: AppContainer, item: RssItem) {
        val created = container.repository.create(
            DownloadCreateRequest(source = LinkParser.sourceFor(item.link), url = item.link, fileName = item.title)
        )
        DownloadService.start(context, listOf(created.id))
    }

    private fun fetch(url: String, proxy: java.net.Proxy?): String {
        val target = URL(url)
        val connection = (proxy?.let { target.openConnection(it) } ?: target.openConnection()) as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("User-Agent", "1-download-manager")
        try {
            if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
            return connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            connection.disconnect()
        }
    }
}

/** Re-reads the feeds every 30 minutes, so the rules download new articles in the background. */
class RssWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? DownloadHubApplication ?: return Result.failure()
        if (app.container.settings.rssFeeds().isEmpty()) return Result.success()
        runCatching { RssSync.refresh(applicationContext, app.container) }
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "download-hub-rss",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<RssWorker>(30, TimeUnit.MINUTES).build()
            )
        }
    }
}
