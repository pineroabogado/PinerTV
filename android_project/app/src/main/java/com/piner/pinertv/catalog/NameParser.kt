
package com.piner.pinertv.catalog

import android.util.Log
import java.util.regex.Pattern

object NameParser {

    data class ParsedName(
        val name: String,
        val year: String?,
        val episodeInfo: String?,
        val season: Int? = null,
        val episode: Int? = null,
        val alternativeTitle: String? = null
    )

    fun cleanName(rawName: String): ParsedName {
        var name = rawName
        
        // 0. Quitar extensiÃ³n de vÃ­deo y sufijos de web de release (.pctreload1.com, .atomixhq.link)
        name = stripVideoExtAndReleaseSites(name)
        
        // Extract Episode/Season Info BEFORE cleaning
        var episodeInfo: String? = null
        var season: Int? = null
        var episode: Int? = null
        
        // Pattern 1: S01E01 or s1e1
        // Group 2: Season, Group 3: Episode
        val regexSxE = Regex("(?i)([Ss](\\d{1,2})[Ee](\\d{1,2}))") 
        regexSxE.find(name)?.let {
            episodeInfo = it.value.uppercase()
            season = it.groupValues[2].toIntOrNull()
            episode = it.groupValues[3].toIntOrNull()
        }

        // Pattern 1b: S02.E03, S02-E03, S02 E03
        if (episodeInfo == null) {
            val regexSxESeparated = Regex("(?i)[Ss](\\d{1,2})[.\\s_-]+[Ee](\\d{1,2})")
            regexSxESeparated.find(name)?.let {
                episodeInfo = it.value.uppercase().replace(Regex("[.\\s_-]+"), "")
                season = it.groupValues[1].toIntOrNull()
                episode = it.groupValues[2].toIntOrNull()
            }
        }
        
        // Pattern 2: 1x01
        // Group 1: Season, Group 2: Episode
        if (episodeInfo == null) {
            val regexNxN = Regex("(?i)(\\d{1,2})\\s*[xX]\\s*(\\d{1,2})")
            regexNxN.find(name)?.let {
                episodeInfo = it.value
                season = it.groupValues[1].toIntOrNull()
                episode = it.groupValues[2].toIntOrNull()
            }
        }

        // Pattern 2b: standalone E03 / Ep.03
        if (episode == null) {
            val regexE = Regex("(?i)(?<![Ss]\\d{1,2})[Ee]\\.?(\\d{1,2})(?![\\d])")
            regexE.find(name)?.let {
                episodeInfo = it.value.uppercase()
                episode = it.groupValues[1].toIntOrNull()
            }
        }
        
        // Pattern 3: Cap./Capitulo 123
        if (episodeInfo == null) {
            val regexCap = Regex("(?i)(Cap\\.|Capitulo|Cap|Episode)\\s*(\\d+)")
            regexCap.find(name)?.let {
                episodeInfo = "${it.groupValues[1]} ${it.groupValues[2]}".trim()
                episode = it.groupValues[2].toIntOrNull()
                // Season unknown from single cap number, usually S1
            }
        }
        
        // Pattern 4: Temporada 1
        // (Only if we haven't found a specific episode, as Temporada is less specific)
        if (episodeInfo == null) {
            val regexSeason = Regex("(?i)(Temporada|Season)\\s*(\\d+)")
            regexSeason.find(name)?.let {
                episodeInfo = "${it.groupValues[1]} ${it.groupValues[2]}".trim()
                season = it.groupValues[2].toIntOrNull()
            }
        }

        // 0b. Extraer aÃ±o ANTES de borrar parÃ©ntesis (Moon (2009), La ballena (2022))
        var year: String? = null
        val yearStandalone = Pattern.compile("\\b(19\\d{2}|20\\d{2})\\b")
        val yearMatcherEarly = yearStandalone.matcher(name)
        if (yearMatcherEarly.find()) {
            year = yearMatcherEarly.group(1)
        }

        // 0c. TÃ­tulo alternativo en parÃ©ntesis: "La ballena (The Whale) (2022)"
        var alternativeTitle: String? = null
        Regex("\\(([^)]+)\\)").findAll(name).forEach { match ->
            val inner = match.groupValues[1].trim()
            if (inner.isNotEmpty() && !inner.matches(Regex("^(19|20)\\d{2}$")) && inner.length >= 3) {
                if (alternativeTitle == null) {
                    alternativeTitle = inner
                }
            }
        }

        // 1. Prefijos (conservador, cerca de GitHub + ID TMDB):
        //    Primero ID TMDB "785084-the-whale...", luego Ã­ndices "01 Title" / "02.Title"
        name = name.replace(Regex("^\\d{4,7}[-_]"), "")
        name = name.replace(Regex("^\\d{1,3}\\.\\s*"), "") // 02.Title / 02. Title
        name = name.replace(Regex("^\\d{1,3}\\s+"), "")   // 02 Title (como GitHub)
        
        // 2. Separate concatenated quality/resolution tags from words
        name = name.replace(Regex("(\\d{3,4}[pP])"), " $1 ") // 1080p, 720p, 2160p
        name = name.replace(Regex("([Mm]1080|[Bb][Dd]1080|4[Kk])"), " $1 ") // M1080, BD1080, 4K
        name = name.replace(Regex("([Rr]emux)"), " $1 ") // Remux
        name = name.replace(Regex("(2160)"), " $1 ") // 2160 (4K resolution)

        // 3. Remove everything between brackets [...] and backticks
        // Replace with SPACE to avoid concatenating words
        name = name.replace(Regex("\\[.*?\\]"), " ")
        name = name.replace(Regex("`.*?`"), " ")
        name = name.replace(Regex("\\(.*?\\)"), " ") 
        
        // Remove unclosed brackets at the end (e.g. "Title [Esp")
        name = name.replace(Regex("\\[.*$"), "")
        name = name.replace(Regex("\\(.*$"), "")

        // 4. Si el aÃ±o no se capturÃ³ antes, buscarlo en el texto restante
        if (year == null) {
            val matcher = yearStandalone.matcher(name)
            if (matcher.find()) {
                year = matcher.group(1)
            }
        }
        if (year != null) {
            name = name.replace(year, " ")
        }

        // 5. Remove typical torrent tags and SERIES tags
        val tags = listOf(
            "1080p", "720p", "2160p", "480p", "4k", "hdr", "sdr", "10bit",
            "web-dl", "webrip", "bluray", "brrip", "bdrip", "dvdrip",
            "remux", "remastered", "2160",
            "x264", "x265", "h264", "h265", "hevc", "avc",
            "ac3", "dts", "truehd", "atmos", "ddp5", "ddp", "aac", "ma",
            "m1080", "bd1080", "microhd",
            "www", "newpct1", "newpct", "wolfmax", "cinepalomitas",
            "descargas2020", "atomohd", "gopihd", "fgt", "aoc", "amzn",
            "mkv", "mp4", "avi", "mov", "wmv",
            "spanish", "english", "hindi", "castellano", "latino",
            "esubs", "subs", "dual", "ch",
            "languagees", "languages", "language",
            "sample", "extended", "unrated", "directors cut", "dvd1", "dvd", "hd",
            // Series tags
            "cap", "capitulo", "episode", "temporada", "season", "s\\d{1,2}e\\d{1,2}", "\\d{1,2}x\\d{1,2}",
            "hdtv", "pctreload", "pctreload\\d+", "atomixhq", "atomix", "hq", "saga", "part", "pt", "cd",
            // basura de dominios / TLDs sueltos tras limpiar releases
            "link", "xyz", "info", "biz", "torrent", "descargas"
        )
        
        for (tag in tags) {
            name = name.replace(Regex("\\b$tag\\.?\\d*\\b", RegexOption.IGNORE_CASE), " ")
        }
        
        // Remove common website domains / TLDs
        name = name.replace(
            Regex("\\b(com|fi|net|org|link|to|cc|me|tv|xyz|info|biz|io|co|uk|es)\\b", RegexOption.IGNORE_CASE),
            " "
        )
        
        // 6. Remove @mentions
        name = name.replace(Regex("@\\w+"), " ")
        
        // 7. Separate camelCase and numbers attached to words
        name = name.replace(Regex("([a-z])([A-Z])"), "$1 $2") // camelCase
        name = name.replace(Regex("([a-zA-Z])(\\d+)"), "$1 $2") // "Letal1" -> "Letal 1"
        name = name.replace(Regex("(\\d+)([a-zA-Z])"), "$1 $2") // "1La" -> "1 La"
        
        // 8. Remove common separators and clean up
        name = name.replace(Regex("[._-]+"), " ")
        name = name.replace(Regex("\\s+"), " ").trim()
        
        // 10. Clean up common Spanish article issues
        name = name.replace(Regex("^[Ll]a\\s+"), "La ")
        name = name.replace(Regex("^[Ee]l\\s+"), "El ")
        name = name.replace(Regex("^[Ll]os\\s+"), "Los ")
        
        // Remove 'true' or 'false' if left over from parsing
        name = name.replace(Regex("\\b(true|false)\\b", RegexOption.IGNORE_CASE), "")

        val cleanedAlt = alternativeTitle
            ?.replace(Regex("[._-]+"), " ")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.length >= 3 }

        return ParsedName(name.trim(), year, episodeInfo, season, episode, cleanedAlt)
    }

    /**
     * Quita `.mkv`/etc. y colas de release tipo `.pctreload1.com` / `.atomixhq.link`
     * que, si se dejan, ensucian el tÃ­tulo y hacen que TMDb confunda "Matrix" con
     * "Matrix Resurrections".
     */
    private fun stripVideoExtAndReleaseSites(raw: String): String {
        var n = raw.trim()
        n = n.replace(
            Regex("\\.(mkv|mp4|avi|mov|wmv|m4v|webm|flv)$", RegexOption.IGNORE_CASE),
            ""
        )
        val siteTlds = "com|net|org|info|link|to|cc|me|tv|xyz|biz|io|co|uk|es|fi"
        val releaseSites = listOf(
            "pctreload\\d*", "newpct\\d*", "atomixhq", "atomix", "atomohd",
            "descargas\\d*", "wolfmax\\d*", "cinepalomitas", "gopihd", "torrent[a-z0-9]*"
        )
        var changed = true
        var guard = 0
        while (changed && guard++ < 8) {
            changed = false
            // .sitio.tld
            val siteDotTld = Regex("(?i)\\.(?:${releaseSites.joinToString("|")})\\.(?:$siteTlds)$")
            if (siteDotTld.containsMatchIn(n)) {
                n = n.replace(siteDotTld, "")
                changed = true
                continue
            }
            // .cualquiercosa.tld al final (p.ej. .pctreload1.com ya cubierto; .foo.link genÃ©rico)
            val anyDotTld = Regex("(?i)\\.([a-z0-9_-]{2,40})\\.(?:$siteTlds)$")
            anyDotTld.find(n)?.let { m ->
                val mid = m.groupValues[1].lowercase()
                // No borrar si parece aÃ±o u otra cosa Ãºtil de tÃ­tulo
                if (mid.toIntOrNull() !in 1900..2099) {
                    n = n.removeRange(m.range)
                    changed = true
                }
            }
            if (changed) continue
            // resto .sitio sin tld
            val siteOnly = Regex("(?i)\\.(?:${releaseSites.joinToString("|")})$")
            if (siteOnly.containsMatchIn(n)) {
                n = n.replace(siteOnly, "")
                changed = true
            }
        }
        return n
    }

    private fun parseSeasonFromPair(pair: String): Int? {
        if (pair.length == 2 && pair[1] == '0') {
            return pair[0].digitToIntOrNull()
        }
        return pair.toIntOrNull()
    }

    private fun tryParseSeasonEpisodeWithQuality(rawName: String): Pair<Int?, Int?>? {
        val qualitySuffix = listOf("720", "1080", "480", "2160")

        Regex("(?i)(?:^|[\\s._-])(\\d{1,2})\\s+(\\d)(\\d{3})\\s*[pP]").find(rawName)?.let { match ->
            if (match.groupValues[3] in qualitySuffix) {
                return parseSeasonFromPair(match.groupValues[1]) to match.groupValues[2].toIntOrNull()
            }
        }

        Regex("(?i)(?:^|[\\s._-])(\\d{1,2})(\\d)(\\d{3})[pP]").find(rawName)?.let { match ->
            if (match.groupValues[3] in qualitySuffix) {
                return parseSeasonFromPair(match.groupValues[1]) to match.groupValues[2].toIntOrNull()
            }
        }

        return null
    }

    /**
     * Interpreta el nÃºmero que sigue a "Cap."/"Capitulo"/"Episode".
     * Formatos tÃ­picos en colecciones por temporadas:
     *   - 3 dÃ­gitos (203) -> temporada 2, episodio 03
     *   - 4 dÃ­gitos (0210) -> temporada 02, episodio 10
     *   - 1-2 dÃ­gitos (8)  -> episodio 8 (temporada desconocida)
     * Se usa [knownSeason] (de "Temporada N") para confirmar la divisiÃ³n.
     */
    private fun interpretChapterNumber(digits: String, knownSeason: Int?): Pair<Int?, Int?> {
        val num = digits.toIntOrNull() ?: return null to null

        when (digits.length) {
            3 -> {
                val firstDigit = digits[0].digitToInt()
                val lastTwo = digits.substring(1).toInt()
                // Dividir como S+EE si coincide con la temporada conocida,
                // o si no hay temporada de referencia.
                if (knownSeason == null || firstDigit == knownSeason) {
                    return firstDigit to lastTwo
                }
                // Si el primer dÃ­gito no concuerda, asumir igualmente S+EE
                // (es el patrÃ³n dominante en estas colecciones).
                return firstDigit to lastTwo
            }
            4 -> {
                val s = digits.substring(0, 2).toInt()
                val e = digits.substring(2).toInt()
                return s to e
            }
            else -> return null to num
        }
    }

    fun parseEpisodeFile(fileName: String, parentFolder: String): ParsedName {
        val rawName = fileName.substringBeforeLast('.')
        var season: Int? = null
        var episode: Int? = null
        var episodeInfo: String? = null

        // 0. Temporada explÃ­cita (prioriza nombre de archivo, luego carpeta)
        Regex("(?i)(?:Season|Temporada)\\s*(\\d{1,2})").find(rawName)?.let {
            season = it.groupValues[1].toIntOrNull()
        }
        if (season == null) {
            Regex("(?i)(?:Season|Temporada)\\s*(\\d{1,2})").find(parentFolder)?.let {
                season = it.groupValues[1].toIntOrNull()
            }
        }

        // 1. S01E01
        Regex("(?i)[Ss](\\d{1,2})[Ee](\\d{1,2})(?=\\D|\\d{3}[pP]|$)").find(rawName)?.let {
            season = it.groupValues[1].toIntOrNull()
            episode = it.groupValues[2].toIntOrNull()
            episodeInfo = "S${it.groupValues[1]}E${it.groupValues[2]}"
        }

        if (episode == null) {
            Regex("(?i)[Ss](\\d{1,2})[.\\s_-]+[Ee][p.]?(\\d{1,2})").find(rawName)?.let {
                season = it.groupValues[1].toIntOrNull()
                episode = it.groupValues[2].toIntOrNull()
                episodeInfo = "S${it.groupValues[1]}E${it.groupValues[2]}"
            }
        }

        // 2. Cap.NNN / Capitulo NNN / Episode NNN (en archivo o carpeta)
        if (episode == null) {
            val capRegex = Regex("(?i)(?:Cap[iÃ­]tulo|Cap|Episode|Episodio|Ep)\\.?\\s*(\\d{1,4})")
            val capMatch = capRegex.find(rawName) ?: capRegex.find(parentFolder)
            capMatch?.let {
                val digits = it.groupValues[1]
                val (s, e) = interpretChapterNumber(digits, season)
                if (s != null) season = s
                episode = e
                if (e != null) episodeInfo = "Cap.$digits"
            }
        }

        // 3. 1x01
        if (episode == null) {
            Regex("(?i)(\\d{1,2})\\s*[xX]\\s*(\\d{1,2})").find(rawName)?.let {
                season = it.groupValues[1].toIntOrNull()
                episode = it.groupValues[2].toIntOrNull()
                episodeInfo = "${it.groupValues[1]}x${it.groupValues[2]}"
            }
        }

        // 4. PatrÃ³n con calidad pegada (p.ej. "20 3720 p")
        if (episode == null) {
            tryParseSeasonEpisodeWithQuality(rawName)?.let { (parsedSeason, parsedEpisode) ->
                if (parsedSeason != null) season = parsedSeason
                episode = parsedEpisode
                if (parsedSeason != null && parsedEpisode != null) {
                    episodeInfo = "S${parsedSeason.toString().padStart(2, '0')}E${parsedEpisode.toString().padStart(2, '0')}"
                }
            }
        }

        // 5. E03 suelto
        if (episode == null) {
            Regex("(?i)(?<![Ss]\\d{1,2})[Ee][p.]?(\\d{1,2})(?=\\D|\\d{3}[pP]|$)").find(rawName)?.let {
                episode = it.groupValues[1].toIntOrNull()
                episodeInfo = "E${it.groupValues[1]}"
            }
        }

        // 6. NÃºmero justo antes de la calidad
        if (episode == null) {
            Regex("(?i)(?:^|[\\s._-])(\\d{1,2})(?:[\\s._-]+)(?:720|1080|480|2160|4k|hd|bluray|web)").find(rawName)?.let {
                val candidate = it.groupValues[1].toIntOrNull()
                if (candidate != null && candidate in 1..50) {
                    episode = candidate
                }
            }
        }

        val parsed = cleanName(fileName)
        val result = parsed.copy(
            season = season ?: parsed.season,
            episode = episode ?: parsed.episode,
            episodeInfo = episodeInfo ?: parsed.episodeInfo
        )
        Log.d(
            "NameParser",
            "parseEpisodeFile file='$fileName' folder='$parentFolder' -> S${result.season} E${result.episode}"
        )
        return result
    }

    fun isSaga(rawName: String): Boolean {
        return rawName.contains("saga", ignoreCase = true)
    }
}

