package com.cloudstream.tr.hub

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.ContextThemeWrapper
import android.widget.CompoundButton
import android.widget.TextView
import com.lagradost.cloudstream3.MainPageData

internal data class HubHomeSection(val id: String, val label: String)

/**
 * The host owns its global home and color theme. These preferences customize
 * only the CloudStreamHub provider and its own plugin settings views.
 */
internal object HubHomePreferences {
    private const val PREFS = "CloudStreamHub_Home_v1"
    private const val KEY_ORDER = "order"
    private const val KEY_HIDDEN = "hidden"

    val sections = listOf(
        HubHomeSection("trending", "Günün Trendleri"),
        HubHomeSection("popular_movies", "Popüler Filmler"),
        HubHomeSection("popular_series", "Popüler Diziler"),
        HubHomeSection("turkish_series", "Türk Dizileri"),
        HubHomeSection("rated_movies", "En Çok Oy Alan Filmler"),
        HubHomeSection("theaters", "Vizyondaki Filmler"),
        HubHomeSection("documentary_movies", "Belgesel Filmler"),
        HubHomeSection("documentary_series", "Belgesel Dizileri"),
        HubHomeSection("animation_movies", "Animasyon Filmleri"),
        HubHomeSection("anime", "Anime ve Animasyon")
    )
    private val identifiers = sections.map { it.id }.toSet()

    data class Config(val order: List<String>, val hidden: Set<String>)
    @Volatile private var config = normalized(emptyList(), emptySet())

    /** Preserve every known section exactly once; do not permit an empty homepage. */
    fun normalized(rawOrder: List<String>, rawHidden: Set<String>): Config {
        val order = (rawOrder.filter { it in identifiers } + sections.map { it.id }).distinct()
        val hidden = rawHidden.intersect(identifiers).let { valid ->
            if (valid.size == identifiers.size) valid - order.first() else valid
        }
        return Config(order, hidden)
    }

    @Synchronized fun snapshot(): Config = config.copy(order = config.order.toList(), hidden = config.hidden.toSet())

    @Synchronized fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        config = normalized(
            prefs.getString(KEY_ORDER, "").orEmpty().split(",").filter(String::isNotBlank),
            prefs.getString(KEY_HIDDEN, "").orEmpty().split(",").filter(String::isNotBlank).toSet()
        )
    }

    @Synchronized fun save(context: Context, value: Config) {
        val next = normalized(value.order, value.hidden)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ORDER, next.order.joinToString(","))
            .putString(KEY_HIDDEN, next.hidden.joinToString(","))
            .apply()
        config = next
    }

    @Synchronized fun isVisible(label: String): Boolean =
        sections.firstOrNull { it.label == label }?.id?.let { it !in config.hidden } ?: false

    fun filtered(pages: List<MainPageData>, value: Config = snapshot()): List<MainPageData> {
        val selected = normalized(value.order, value.hidden)
        val byLabel = pages.associateBy { it.name }
        return selected.order.mapNotNull { id ->
            if (id in selected.hidden) null else sections.firstOrNull { it.id == id }
                ?.let { byLabel[it.label] }
        }
    }
}

internal object HubAppearancePreferences {
    private const val PREFS = "CloudStreamHub_Appearance_v1"
    private const val KEY_MODE = "dialog_mode"
    private const val KEY_ACCENT = "accent"

    const val SYSTEM = "SYSTEM"
    const val LIGHT = "LIGHT"
    const val DARK = "DARK"

    data class Accent(val id: String, val label: String, val hex: String)
    val accents = listOf(
        Accent("BLUE", "Mavi", "#2196F3"),
        Accent("PURPLE", "Mor", "#9C27B0"),
        Accent("TEAL", "Turkuaz", "#009688"),
        Accent("GREEN", "Yeşil", "#43A047"),
        Accent("ORANGE", "Turuncu", "#F57C00"),
        Accent("RED", "Kırmızı", "#E53935")
    )
    val modes = listOf(SYSTEM, LIGHT, DARK)

    data class Config(val mode: String = SYSTEM, val accent: String = "BLUE")
    @Volatile private var config = Config()

    fun normalized(mode: String?, accent: String?): Config = Config(
        mode?.takeIf(modes::contains) ?: SYSTEM,
        accent?.takeIf { id -> accents.any { it.id == id } } ?: "BLUE"
    )

    fun snapshot(): Config = config

    @Synchronized fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        config = normalized(prefs.getString(KEY_MODE, SYSTEM), prefs.getString(KEY_ACCENT, "BLUE"))
    }

    @Synchronized fun save(context: Context, value: Config) {
        val next = normalized(value.mode, value.accent)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MODE, next.mode).putString(KEY_ACCENT, next.accent).apply()
        config = next
    }

    fun settingsContext(context: Context, value: Config = snapshot()): Context = when (value.mode) {
        LIGHT -> ContextThemeWrapper(context, android.R.style.Theme_Material_Light_Dialog_Alert)
        DARK -> ContextThemeWrapper(context, android.R.style.Theme_Material_Dialog_Alert)
        else -> context
    }

    fun accentColor(value: Config = snapshot()): Int = Color.parseColor(
        accents.firstOrNull { it.id == value.accent }?.hex ?: accents.first().hex
    )

    fun tint(text: TextView, value: Config = snapshot()) = text.setTextColor(accentColor(value))

    fun tint(check: CompoundButton, value: Config = snapshot()) {
        check.buttonTintList = ColorStateList.valueOf(accentColor(value))
    }
}
