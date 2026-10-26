package com.cloudstream.tr.hub

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.lagradost.cloudstream3.actions.OpenInAppAction
import com.lagradost.cloudstream3.actions.updateDurationAndPosition
import com.lagradost.cloudstream3.ui.player.Torrent
import com.lagradost.cloudstream3.ui.result.LinkLoadingResult
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.DataStoreHelper.getViewPos
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.txt

/**
 * Native Just Player handoff: supports Dolby Vision Profile 7/8 fallback,
 * Dolby Atmos (TrueHD / E-AC3 JOC) passthrough and multichannel PCM decoding.
 * Package: com.brouken.player
 */
class HubTorrentJustPlayerAction : OpenInAppAction(
    appName = txt("Just Player (Dolby Vision / Atmos)"),
    packageName = "com.brouken.player",
    intentClass = "com.brouken.player.PlayerActivity",
    action = Intent.ACTION_VIEW
) {
    override val name = txt("CloudStreamHub: Torrent → Just Player (DV / Atmos)")
    override val isPlayer = false
    override val oneSource = true
    override val sourceTypes = setOf(ExtractorLinkType.MAGNET, ExtractorLinkType.TORRENT)

    override suspend fun putExtra(
        context: Context,
        intent: Intent,
        video: ResultEpisode,
        result: LinkLoadingResult,
        index: Int?
    ) {
        val converted = prepareTorrentStream(result.links, index,
            consent = { requestTorrentConsent(context) },
            readiness = { prepareTorrentForExternalPlayer(context, it.url) },
            transform = { Torrent.transformLink(it).first })
        intent.setDataAndType(Uri.parse(converted.url), "video/*")
        intent.putExtra("title", video.name)
        intent.putExtra("secure_uri", true)
        intent.putExtra("return_result", true)
        intent.putExtra("position", (getViewPos(video.id)?.position ?: 0L).toInt())
        if (result.subs.isNotEmpty()) {
            intent.putExtra("subs", result.subs.map { Uri.parse(it.url) }.toTypedArray())
            intent.putExtra("subs.name", result.subs.map { it.name }.toTypedArray())
        }
    }

    override fun onResult(activity: Activity, intent: Intent?) {
        val position = intent?.getIntExtra("position", -1) ?: -1
        val duration = intent?.getIntExtra("duration", -1) ?: -1
        updateDurationAndPosition(position.toLong(), duration.toLong())
    }
}
