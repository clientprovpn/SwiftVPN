package ir.swiftvpn.engine

import android.content.Context

/**
 * App preferences: which profile the tile toggles, favourites, and theme.
 *
 * The tile runs outside the Activity lifecycle, so these must be persisted
 * rather than held in memory.
 */
class ProfileStore(context: Context) {

    private val prefs =
        context.getSharedPreferences("swiftvpn_prefs", Context.MODE_PRIVATE)

    // ------------------------------------------------------------ tile target

    fun select(profile: Profile) {
        prefs.edit()
            .putString(KEY_TILE_UUID, profile.uuid)
            .putString(KEY_TILE_NAME, profile.name)
            .apply()
    }

    fun selected(): TileTarget? {
        val uuid = prefs.getString(KEY_TILE_UUID, null) ?: return null
        val name = prefs.getString(KEY_TILE_NAME, null) ?: return null
        return TileTarget(uuid, name)
    }

    fun selectedName(): String? = prefs.getString(KEY_TILE_NAME, null)

    fun clearSelectionIfMatches(uuid: String) {
        if (prefs.getString(KEY_TILE_UUID, null) == uuid) {
            prefs.edit().remove(KEY_TILE_UUID).remove(KEY_TILE_NAME).apply()
        }
    }

    // ------------------------------------------------------------- favourites

    fun favourites(): Set<String> =
        prefs.getStringSet(KEY_FAVOURITES, emptySet()) ?: emptySet()

    fun toggleFavourite(uuid: String) {
        val current = favourites().toMutableSet()
        if (!current.remove(uuid)) current.add(uuid)
        prefs.edit().putStringSet(KEY_FAVOURITES, current).apply()
    }

    // ------------------------------------------------------------------ theme

    /**
     * Stored by NAME, not ordinal.
     *
     * The old build persisted an ordinal with SYSTEM=0, LIGHT=1, DARK=2. Now
     * that SYSTEM is gone those numbers would shift, silently turning a saved
     * DARK into an out-of-range read and a saved SYSTEM into LIGHT. Reading a
     * name sidesteps that, and the key was bumped so the stale int is ignored.
     */
    var themeMode: ThemeMode
        get() = prefs.getString(KEY_THEME, null)
            ?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } }
            ?: ThemeMode.DARK
        set(value) = prefs.edit().putString(KEY_THEME, value.name).apply()

    // ------------------------------------------------------------------- sort

    var sortMode: SortMode
        get() = SortMode.entries.getOrNull(prefs.getInt(KEY_SORT, 0)) ?: SortMode.NAME
        set(value) = prefs.edit().putInt(KEY_SORT, value.ordinal).apply()

    // ----------------------------------------------------------------- filter

    /**
     * Which protocol the list is filtered to, or null for all of them.
     *
     * Stored by NAME, not ordinal — the same trap the theme setting hit earlier.
     * A future protocol inserted into the enum would shift every ordinal and
     * silently change what a saved filter means.
     */
    var protocolFilter: Protocol?
        get() = prefs.getString(KEY_FILTER, null)
            ?.let { name -> Protocol.entries.firstOrNull { it.name == name } }
        set(value) = prefs.edit().apply {
            if (value == null) remove(KEY_FILTER) else putString(KEY_FILTER, value.name)
        }.apply()

    private companion object {
        const val KEY_TILE_UUID = "tile_profile_uuid"
        const val KEY_TILE_NAME = "tile_profile_name"
        const val KEY_FAVOURITES = "favourites"
        const val KEY_THEME = "theme_mode_v2"
        const val KEY_SORT = "sort_mode"
        const val KEY_FILTER = "protocol_filter"
    }
}

data class TileTarget(val uuid: String, val name: String)

enum class ThemeMode { LIGHT, DARK }

enum class SortMode { NAME, RECENT, FAVOURITE }
