package com.cloudstream.tr.hub

import android.app.AlertDialog
import android.content.Context
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.media.MediaCodecList
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.view.Gravity
import com.cloudstream.tr.core.diagnostics.DiagnosticLogger
import com.cloudstream.tr.core.resolvers.DebridConfig
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class CloudStreamHubPlugin : Plugin() {
    override fun load(context: Context) {
        // Preferences must load before super.load registers the Hub provider.
        DebridConfig.initFromPreferences(context.applicationContext)
        HubPerformanceSettings.load(context.applicationContext)
        HubHomePreferences.load(context.applicationContext)
        HubAppearancePreferences.load(context.applicationContext)
        super.load(context)
        openSettings = { settingsContext -> showHubSettings(settingsContext) }
        registerVideoClickAction(HubTorrentVlcAction())
        registerVideoClickAction(HubTorrentMxAction())
    }

    override fun load() {
        HubFederationLogcatBridge.install()
        registerMainAPI(CloudStreamHub())
        registerExtractorAPI(com.cloudstream.tr.animecix.TauVideo())
    }

    private fun showHubSettings(context: Context) {
        DebridConfig.initFromPreferences(context)
        HubHomePreferences.load(context)
        HubAppearancePreferences.load(context)
        val appearance = HubAppearancePreferences.snapshot()
        val themedContext = HubAppearancePreferences.settingsContext(context, appearance)

        val layout = LinearLayout(themedContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 30)
        }

        val rdLabel = TextView(themedContext).apply {
            text = "Real-Debrid API Token (Opsiyonel):"
            textSize = 14f
        }
        val rdInput = EditText(themedContext).apply {
            hint = "Real-Debrid API Key giriniz..."
            setText(DebridConfig.realDebridToken ?: "")
        }

        val torboxLabel = TextView(themedContext).apply {
            text = "Torbox API Token (Opsiyonel):"
            textSize = 14f
            setPadding(0, 20, 0, 0)
        }
        val torboxInput = EditText(themedContext).apply {
            hint = "Torbox API Key giriniz..."
            setText(DebridConfig.torboxToken ?: "")
        }

        val torrentCheck = CheckBox(themedContext).apply {
            text = "P2P Torrent Kaynaklarını Göster (Torrentio/YTS ve diğer sağlayıcılar)"
            isChecked = DebridConfig.enableTorrentSources
            setPadding(0, 20, 0, 0)
        }

        val cdnCheck = CheckBox(themedContext).apply {
            text = "4KHDHub Doğrudan CDN Akışlarını Önceliklendir"
            isChecked = DebridConfig.prioritizeDirectCdn
        }

        val profileLabel = TextView(themedContext).apply {
            text = "Oynatma profili (seçim yalnız kaynak sıralamasını etkiler)"
            setPadding(0, 22, 0, 0)
        }
        val profileValues = listOf(
            HubPerformanceSettings.AUTO,
            HubPerformanceSettings.COMPATIBLE,
            HubPerformanceSettings.FEATURE
        )
        val profileSpinner = Spinner(themedContext).apply {
            adapter = ArrayAdapter(themedContext, android.R.layout.simple_spinner_dropdown_item,
                listOf("Otomatik", "Uyumluluk öncelikli", "4K / REMUX öncelikli"))
            setSelection(profileValues.indexOf(HubPerformanceSettings.profile).coerceAtLeast(0))
        }
        val hardwareInfo = TextView(themedContext).apply {
            textSize = 12f
            text = "Donanımsal HEVC: " + try {
                if (MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                    !info.isEncoder && info.supportedTypes.any { type -> type.equals("video/hevc", true) }
                }) "bildiriliyor (HDR / ses ayrı doğrulanmalı)" else "bildirilmiyor"
            } catch (_: Exception) { "sorgulanamadı" }
        }
        val diagnosticsButton = Button(themedContext).apply {
            text = "Oynatma / kaynak tanılama kayıtlarını göster"
            setOnClickListener {
                AlertDialog.Builder(themedContext)
                    .setTitle("CloudStreamHub performans ölçümleri")
                    .setMessage(HubPerformanceTelemetry.report())
                    .setPositiveButton("Kapat", null)
                    .setNeutralButton("Kayıtları temizle") { _, _ -> HubPerformanceTelemetry.clear() }
                    .show()
            }
        }

        // CloudStream itself manages its global theme. The following colors
        // apply only to the Hub's own dialogs and controls.
        val appearanceTitle = TextView(themedContext).apply {
            text = "Görünüm ve tema"
            textSize = 17f
            setPadding(0, 26, 0, 6)
            HubAppearancePreferences.tint(this, appearance)
        }
        val dialogThemeLabel = TextView(themedContext).apply {
            text = "Eklenti ayar penceresi teması"
        }
        val themeModes = HubAppearancePreferences.modes
        val modeSpinner = Spinner(themedContext).apply {
            adapter = ArrayAdapter(themedContext, android.R.layout.simple_spinner_dropdown_item,
                listOf("Sistem varsayılanı", "Açık", "Koyu"))
            setSelection(themeModes.indexOf(appearance.mode).coerceAtLeast(0))
        }
        val accentLabel = TextView(themedContext).apply {
            text = "Vurgu rengi"
        }
        val accentSpinner = Spinner(themedContext).apply {
            adapter = ArrayAdapter(themedContext, android.R.layout.simple_spinner_dropdown_item,
                HubAppearancePreferences.accents.map { it.label })
            setSelection(HubAppearancePreferences.accents.indexOfFirst { it.id == appearance.accent }
                .coerceAtLeast(0))
        }
        val appearanceHint = TextView(themedContext).apply {
            text = "Tema yalnız CloudStreamHub ayarlarını etkiler; CloudStream uygulamasının genel teması kendi ayarlarından değiştirilir. Görünüm için pencereyi yeniden açın."
            textSize = 12f
            setPadding(0, 0, 0, 18)
        }

        val homeTitle = TextView(themedContext).apply {
            text = "Ana sayfa kişiselleştirme"
            textSize = 17f
            setPadding(0, 14, 0, 6)
            HubAppearancePreferences.tint(this, appearance)
        }
        val homeHint = TextView(themedContext).apply {
            text = "İstediğiniz kategorileri kapatın. Yukarı ve aşağı tuşlarıyla sıralayın. CloudStreamHub ana sayfasında uygulanır; gerekirse uygulamayı yeniden açın."
            textSize = 12f
            setPadding(0, 0, 0, 12)
        }
        val originalHome = HubHomePreferences.snapshot()
        val homeOrder = originalHome.order.toMutableList()
        val homeHidden = originalHome.hidden.toMutableSet()
        val homeRows = LinearLayout(themedContext).apply {
            orientation = LinearLayout.VERTICAL
        }

        val arrowWidth = (56 * themedContext.resources.displayMetrics.density).toInt()
        fun renderHomeRows() {
            homeRows.removeAllViews()
            for ((index, sectionId) in homeOrder.withIndex()) {
                val title = HubHomePreferences.sections.first { it.id == sectionId }.label
                val row = LinearLayout(themedContext).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val check = CheckBox(themedContext).apply {
                    text = title
                    textSize = 13f
                    isChecked = sectionId !in homeHidden
                    layoutParams = LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    HubAppearancePreferences.tint(this, appearance)
                    setOnCheckedChangeListener { _, enabled ->
                        if (!enabled && homeHidden.size == homeOrder.size - 1) {
                            isChecked = true
                            Toast.makeText(context, "En az bir ana sayfa kategorisi açık kalmalı.", Toast.LENGTH_SHORT).show()
                        } else if (enabled) {
                            homeHidden.remove(sectionId)
                        } else {
                            homeHidden.add(sectionId)
                        }
                    }
                }
                val up = Button(themedContext).apply {
                    text = "↑"
                    contentDescription = title + " yukarı taşı"
                    isEnabled = index > 0
                    setOnClickListener {
                        homeOrder.removeAt(index)
                        homeOrder.add(index - 1, sectionId)
                        renderHomeRows()
                    }
                }
                val down = Button(themedContext).apply {
                    text = "↓"
                    contentDescription = title + " aşağı taşı"
                    isEnabled = index < homeOrder.lastIndex
                    setOnClickListener {
                        homeOrder.removeAt(index)
                        homeOrder.add(index + 1, sectionId)
                        renderHomeRows()
                    }
                }
                row.addView(check)
                row.addView(up, LinearLayout.LayoutParams(arrowWidth,
                    LinearLayout.LayoutParams.WRAP_CONTENT))
                row.addView(down, LinearLayout.LayoutParams(arrowWidth,
                    LinearLayout.LayoutParams.WRAP_CONTENT))
                homeRows.addView(row)
            }
        }
        renderHomeRows()
        layout.addView(appearanceTitle)
        layout.addView(dialogThemeLabel)
        layout.addView(modeSpinner)
        layout.addView(accentLabel)
        layout.addView(accentSpinner)
        layout.addView(appearanceHint)
        layout.addView(homeTitle)
        layout.addView(homeHint)
        layout.addView(homeRows)

        layout.addView(profileLabel)
        layout.addView(profileSpinner)
        layout.addView(hardwareInfo)
        layout.addView(diagnosticsButton)
        layout.addView(rdLabel)
        layout.addView(rdInput)
        layout.addView(torboxLabel)
        layout.addView(torboxInput)
        layout.addView(torrentCheck)
        layout.addView(cdnCheck)

        val scroll = ScrollView(themedContext).apply {
            addView(layout)
        }

        AlertDialog.Builder(themedContext)
            .setTitle("CloudStreamHub Ayarları")
            .setView(scroll)
            .setPositiveButton("Kaydet") { _, _ ->
                DebridConfig.saveToPreferences(
                    context = context,
                    rdToken = rdInput.text.toString(),
                    torbox = torboxInput.text.toString(),
                    enableTorrents = torrentCheck.isChecked,
                    prioritizeCdn = cdnCheck.isChecked,
                    audioPref = DebridConfig.audioPreference
                )
                HubPerformanceSettings.save(context, profileValues[profileSpinner.selectedItemPosition])
                HubHomePreferences.save(context, HubHomePreferences.Config(homeOrder, homeHidden))
                HubAppearancePreferences.save(context, HubAppearancePreferences.Config(
                    themeModes[modeSpinner.selectedItemPosition],
                    HubAppearancePreferences.accents[accentSpinner.selectedItemPosition].id
                ))
                Toast.makeText(context,
                    "Ayarlar kaydedildi. Ana sayfa ve tema için CloudStream'i yeniden açın.",
                    Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("İptal", null)
            .show()
    }
}

private object HubFederationLogcatBridge {
    private var installed = false

    fun install() {
        synchronized(this) {
            if (installed) return

            DiagnosticLogger.addListener { event ->
                if (event.message.startsWith("Federation result:")) {
                    val message = event.message
                    val result = when {
                        "no confident match" in message -> "no-confident-match"
                        "provider.load returned no response" in message -> "load-no-response"
                        "no movie or requested episode link data" in message -> "no-link-data"
                        "confident " in message -> "confident-match"
                        else -> Regex(
                            "raw=(\\d+), providerEmitted=(\\d+).*valid=(\\d+), indeterminate=(\\d+), invalid=(\\d+), duplicates=(\\d+), dropped=(\\d+)"
                        ).find(message)?.let { match ->
                            val (raw, emitted, valid, indeterminate, invalid, duplicates, dropped) = match.destructured
                            "streams raw=$raw emitted=$emitted valid=$valid indeterminate=$indeterminate invalid=$invalid duplicates=$duplicates dropped=$dropped"
                        } ?: "federation-result"
                    }
                    Log.i("CloudStreamHub", "${event.provider} [${event.stage}] $result")
                }
            }
            installed = true
        }
    }
}
