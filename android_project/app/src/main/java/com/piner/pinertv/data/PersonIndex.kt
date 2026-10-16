package com.piner.pinertv.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.piner.pinertv.catalog.MediaItem
import java.util.concurrent.ConcurrentHashMap

/**
 * Índice local persona → títulos del catálogo + caches de credits/perfil TMDB.
 * Permite mostrar filmografía al instante al abrir ficha de actor.
 */
object PersonIndex {

    private const val TAG = "PinerTV-PersonIndex"
    private const val PREFS = "pinertv_person_index"
    private const val KEY_LINKS = "links"
    private const val KEY_CREDITS = "credits"
    private const val KEY_PROFILES = "profiles"

    private val gson = Gson()
    private val links = ConcurrentHashMap<Int, MutableSet<String>>()
    private val creditsCache = ConcurrentHashMap<Int, List<CachedCredit>>()
    private val profileCache = ConcurrentHashMap<Int, CachedProfile>()

    @Volatile private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        loadFromDisk()
    }

    /** Vincula actores del elenco con un título local (p.ej. al pintar Details). */
    fun linkCast(mediaId: String, personIds: Collection<Int>) {
        if (mediaId.isBlank() || personIds.isEmpty()) return
        var dirty = false
        for (pid in personIds) {
            if (pid <= 0) continue
            val set = links.getOrPut(pid) { ConcurrentHashMap.newKeySet() }
            if (set.add(mediaId)) dirty = true
        }
        if (dirty) persistLinks()
    }

    fun linkPerson(personId: Int, mediaId: String) {
        if (personId <= 0 || mediaId.isBlank()) return
        val set = links.getOrPut(personId) { ConcurrentHashMap.newKeySet() }
        if (set.add(mediaId)) persistLinks()
    }

    /** Títulos locales ya indexados para este actor (memoria, sin red). */
    fun localTitlesFor(personId: Int): List<MediaItem> {
        val ids = links[personId] ?: return emptyList()
        if (ids.isEmpty()) return emptyList()
        val all = CatalogCache.movies + CatalogCache.series
        val byId = all.associateBy { it.id }
        return ids.mapNotNull { byId[it] }
            .distinctBy { it.id }
            .sortedBy { it.title.lowercase() }
    }

    fun cachedCredits(personId: Int): List<CachedCredit>? = creditsCache[personId]

    fun putCredits(personId: Int, credits: List<CachedCredit>) {
        if (personId <= 0) return
        creditsCache[personId] = credits
        persistCredits()
    }

    fun cachedProfile(personId: Int): CachedProfile? = profileCache[personId]

    fun putProfile(personId: Int, profile: CachedProfile) {
        if (personId <= 0) return
        profileCache[personId] = profile
        persistProfiles()
    }

    /** Tras cruzar credits con catálogo, refuerza el índice. */
    fun indexMatches(personId: Int, matches: List<MediaItem>) {
        if (personId <= 0 || matches.isEmpty()) return
        var dirty = false
        val set = links.getOrPut(personId) { ConcurrentHashMap.newKeySet() }
        for (m in matches) {
            if (set.add(m.id)) dirty = true
        }
        if (dirty) persistLinks()
    }

    data class CachedCredit(
        val id: Int,
        val title: String,
        val mediaType: String? = null
    )

    data class CachedProfile(
        val id: Int,
        val name: String,
        val biography: String,
        val profileUrl: String?,
        val knownFor: String
    )

    private fun loadFromDisk() {
        val p = prefs ?: return
        try {
            val linksJson = p.getString(KEY_LINKS, null)
            if (!linksJson.isNullOrBlank()) {
                val type = object : TypeToken<Map<String, List<String>>>() {}.type
                val map: Map<String, List<String>> = gson.fromJson(linksJson, type) ?: emptyMap()
                links.clear()
                for ((k, v) in map) {
                    val id = k.toIntOrNull() ?: continue
                    links[id] = ConcurrentHashMap.newKeySet<String>().also { it.addAll(v) }
                }
            }
            val creditsJson = p.getString(KEY_CREDITS, null)
            if (!creditsJson.isNullOrBlank()) {
                val type = object : TypeToken<Map<String, List<CachedCredit>>>() {}.type
                val map: Map<String, List<CachedCredit>> = gson.fromJson(creditsJson, type) ?: emptyMap()
                creditsCache.clear()
                for ((k, v) in map) {
                    val id = k.toIntOrNull() ?: continue
                    creditsCache[id] = v
                }
            }
            val profilesJson = p.getString(KEY_PROFILES, null)
            if (!profilesJson.isNullOrBlank()) {
                val type = object : TypeToken<Map<String, CachedProfile>>() {}.type
                val map: Map<String, CachedProfile> = gson.fromJson(profilesJson, type) ?: emptyMap()
                profileCache.clear()
                for ((k, v) in map) {
                    val id = k.toIntOrNull() ?: continue
                    profileCache[id] = v
                }
            }
            Log.d(TAG, "Cargado: ${links.size} actores, ${creditsCache.size} credits, ${profileCache.size} perfiles")
        } catch (e: Exception) {
            Log.w(TAG, "Error cargando índice", e)
        }
    }

    private fun persistLinks() {
        val p = prefs ?: return
        val map = links.entries.associate { it.key.toString() to it.value.toList() }
        p.edit().putString(KEY_LINKS, gson.toJson(map)).apply()
    }

    private fun persistCredits() {
        val p = prefs ?: return
        // Limitar tamaño: últimos ~200 actores
        val trimmed = creditsCache.entries
            .sortedByDescending { it.key }
            .take(200)
            .associate { it.key.toString() to it.value }
        p.edit().putString(KEY_CREDITS, gson.toJson(trimmed)).apply()
    }

    private fun persistProfiles() {
        val p = prefs ?: return
        val trimmed = profileCache.entries
            .sortedByDescending { it.key }
            .take(200)
            .associate { it.key.toString() to it.value }
        p.edit().putString(KEY_PROFILES, gson.toJson(trimmed)).apply()
    }
}
