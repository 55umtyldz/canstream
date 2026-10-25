package com.cloudstream.tr.core.resolvers

import android.content.Context
import com.lagradost.cloudstream3.app

object DebridConfig {
    private const val PREFS_NAME = "CloudStreamHub_Settings"
    private const val KEY_REAL_DEBRID = "real_debrid_token"
    private const val KEY_TORBOX = "torbox_token"
    private const val KEY_ALL_DEBRID = "all_debrid_token"
    private const val KEY_ENABLE_TORRENT = "enable_torrent_sources"
    private const val KEY_PRIORITIZE_CDN = "prioritize_direct_cdn"
    private const val KEY_AUDIO_PREF = "audio_preference"
    private const val KEY_MIN_SEEDS = "min_seeds"
    private const val KEY_MAX_TORRENT_SIZE_GB = "max_torrent_size_gb"
    private const val KEY_TORRSERVER_URL = "torrserver_url"

    var realDebridToken: String? = null
    var torboxToken: String? = null
    var allDebridToken: String? = null
    var enableTorrentSources: Boolean = true
    var prioritizeDirectCdn: Boolean = true
    var audioPreference: String = "ALL" // "ALL", "TR_DUB", "ORIGINAL"
    var minSeeds: Int = 3
    var maxTorrentSizeGb: Int = 0 // 0 = unlimited
    var torrServerUrl: String = "http://127.0.0.1:8080"

    fun initFromPreferences(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            realDebridToken = prefs.getString(KEY_REAL_DEBRID, null)
            torboxToken = prefs.getString(KEY_TORBOX, null)
            allDebridToken = prefs.getString(KEY_ALL_DEBRID, null)
            enableTorrentSources = prefs.getBoolean(KEY_ENABLE_TORRENT, true)
            prioritizeDirectCdn = prefs.getBoolean(KEY_PRIORITIZE_CDN, true)
            audioPreference = prefs.getString(KEY_AUDIO_PREF, "ALL") ?: "ALL"
            minSeeds = prefs.getInt(KEY_MIN_SEEDS, 3).coerceAtLeast(1)
            maxTorrentSizeGb = prefs.getInt(KEY_MAX_TORRENT_SIZE_GB, 0).coerceAtLeast(0)
            torrServerUrl = prefs.getString(KEY_TORRSERVER_URL, "http://127.0.0.1:8080") ?: "http://127.0.0.1:8080"
        } catch (_: Exception) {}
    }

    fun saveToPreferences(
        context: Context,
        rdToken: String?,
        torbox: String?,
        allDebrid: String? = null,
        enableTorrents: Boolean,
        prioritizeCdn: Boolean,
        audioPref: String,
        minimumSeeds: Int = 3,
        maxSizeGb: Int = 0,
        torrServer: String = "http://127.0.0.1:8080"
    ) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(KEY_REAL_DEBRID, rdToken?.trim()?.ifBlank { null })
                .putString(KEY_TORBOX, torbox?.trim()?.ifBlank { null })
                .putString(KEY_ALL_DEBRID, allDebrid?.trim()?.ifBlank { null })
                .putBoolean(KEY_ENABLE_TORRENT, enableTorrents)
                .putBoolean(KEY_PRIORITIZE_CDN, prioritizeCdn)
                .putString(KEY_AUDIO_PREF, audioPref)
                .putInt(KEY_MIN_SEEDS, minimumSeeds.coerceAtLeast(1))
                .putInt(KEY_MAX_TORRENT_SIZE_GB, maxSizeGb.coerceAtLeast(0))
                .putString(KEY_TORRSERVER_URL, torrServer.trim().ifBlank { "http://127.0.0.1:8080" })
                .apply()

            realDebridToken = rdToken?.trim()?.ifBlank { null }
            torboxToken = torbox?.trim()?.ifBlank { null }
            allDebridToken = allDebrid?.trim()?.ifBlank { null }
            enableTorrentSources = enableTorrents
            prioritizeDirectCdn = prioritizeCdn
            audioPreference = audioPref
            minSeeds = minimumSeeds.coerceAtLeast(1)
            maxTorrentSizeGb = maxSizeGb.coerceAtLeast(0)
            torrServerUrl = torrServer.trim().ifBlank { "http://127.0.0.1:8080" }
        } catch (_: Exception) {}
    }

    suspend fun checkTorrServerAlive(customUrl: String? = null): Boolean {
        val target = (customUrl ?: torrServerUrl).trimEnd('/')
        return try {
            val resp = app.get("$target/echo", timeout = 3)
            resp.code in 200..299
        } catch (_: Exception) {
            try {
                val resp = app.get(target, timeout = 3)
                resp.code in 200..299
            } catch (_: Exception) {
                false
            }
        }
    }

    fun getActiveDebridPrefix(): String? {
        realDebridToken?.takeIf { it.isNotBlank() }?.let { return "realdebrid=$it" }
        torboxToken?.takeIf { it.isNotBlank() }?.let { return "torbox=$it" }
        allDebridToken?.takeIf { it.isNotBlank() }?.let { return "alldebrid=$it" }
        return null
    }

    val isDebridEnabled: Boolean
        get() = getActiveDebridPrefix() != null
}
