package com.sololatino.ext

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

/**
 * Provider para https://sololatino.net
 *
 * Selectores verificados contra la estructura actual del sitio:
 *  - Tarjetas de catálogo:  div.card  (a[href], img.card__poster, .card__title, .card__year, .card__rating)
 *  - Listados paginados:    /peliculas?page=N  (hasNext = link[rel=next])
 *  - Busqueda:              /buscar?q=
 *  - Ficha:                 h1 + img.w-44 (poster) + .detail-hero__bg (fondo)
 *                           + p.text-sm.leading-relaxed (sinopsis)
 *  - Episodios:             div[data-season-panel] > a.ep-item (p.ep-num -> "E12")
 *  - Reproductores:         button.server-btn[data-player-token]
 *                           -> POST /api/player-url  (X-CSRF-TOKEN) -> { url, type }
 */
class SoloLatinoProvider : MainAPI() {
    override var mainUrl = "https://sololatino.net"
    override var name = "SoloLatino"
    override var lang = "mx"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.Cartoon,
    )

    override val mainPage = mainPageOf(
        "peliculas" to "Películas",
        "series" to "Series",
        "animes" to "Animes",
        "doramas" to "Doramas",
        "peliculas?genero=animacion&sort=popular" to "Cartoons",
    )

    // ---------- helpers ----------

    private fun Element.toSearchResult(): SearchResponse? {
        val link = this.selectFirst("a")?.attr("href") ?: return null
        val title = this.selectFirst("p.card__title")?.text()
            ?: this.selectFirst("span.card__title")?.text()
            ?: this.selectFirst("a img")?.attr("alt")
            ?: return null
        val poster = this.selectFirst("img.card__poster")?.attr("src")
            ?: this.selectFirst("a img")?.attr("src")
        val year = this.selectFirst("span.card__year")?.text()?.trim()?.toIntOrNull()
        val type = if (link.contains("/pelicula/")) TvType.Movie else TvType.TvSeries
        return newMovieSearchResponse(title, link, type) {
            this.posterUrl = poster
            this.year = year
        }
    }

    private suspend fun catalogDocument(url: String) = app.get(url).document

    // ---------- main page ----------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val separator = if (request.data.contains("?")) "&" else "?"
        val document = catalogDocument("$mainUrl/${request.data}${separator}page=$page")
        val home = document.select("div.card").mapNotNull { it.toSearchResult() }
        // El sitio marca la siguiente pagina con <link rel="next"> (verificado en /peliculas?page=2)
        val hasNext = document.selectFirst("link[rel=next]") != null
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false
            ),
            hasNext = hasNext
        )
    }

    // ---------- search ----------

    override suspend fun search(query: String): List<SearchResponse> {
        val document = catalogDocument("$mainUrl/buscar?q=$query")
        return document.select("div.card").mapNotNull { it.toSearchResult() }
    }

    // ---------- load ----------

    override suspend fun load(url: String): LoadResponse? {
        val doc = catalogDocument(url)
        val tvType = if (url.contains("/pelicula/")) TvType.Movie else TvType.TvSeries

        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("img.w-44")?.attr("alt")
            ?: return null
        val poster = doc.selectFirst("img.w-44")?.attr("src")
        val backimage = doc.selectFirst(".detail-hero__bg")?.attr("style")
            ?.substringAfter("url('")?.substringBefore("');")
        val description = doc.selectFirst("p.text-sm.leading-relaxed")?.text()?.trim()
        val year = doc.selectFirst("div.flex.flex-wrap.items-center.gap-4.text-sm > span")
            ?.text()?.trim()?.toIntOrNull()
        val tags = doc.select("a[href*='/genero/']").mapNotNull { it.text()?.trim() }.distinct()
        val recommendations = doc.select("div[id*=scroll-related] div.card").mapNotNull { it.toSearchResult() }

        val episodes = doc.select("div[data-season-panel]").flatMap { panel ->
            val season = panel.attr("data-season-panel").toIntOrNull()
            panel.select("a.ep-item").mapIndexed { idx, ep ->
                val url = ep.attr("href")
                val name = ep.selectFirst("p.text-sm.font-semibold.text-white.leading-tight")?.text()?.trim()
                val img = ep.selectFirst("img.ep-thumb")?.attr("src")
                // p.ep-num tiene formato "E12"; si no se puede parsear, se usa el orden
                val episode = ep.selectFirst("p.ep-num")?.text()?.trim()
                    ?.removePrefix("E")?.removePrefix("e")?.toIntOrNull() ?: (idx + 1)
                newEpisode(url) {
                    this.name = name
                    this.season = season
                    this.episode = episode
                    this.posterUrl = img
                }
            }
        }

        return when (tvType) {
            TvType.TvSeries -> newTvSeriesLoadResponse(title, url, tvType, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backimage ?: poster
                this.plot = description
                this.tags = tags
                this.year = year
                this.recommendations = recommendations
            }

            TvType.Movie -> newMovieLoadResponse(title, url, tvType, url) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backimage ?: poster
                this.plot = description
                this.tags = tags
                this.year = year
                this.recommendations = recommendations
            }

            else -> null
        }
    }

    // ---------- links ----------

    private fun playerHeaders(csrf: String) = mapOf(
        "Content-Type" to "application/json",
        "X-CSRF-TOKEN" to csrf,
        "Accept" to "application/json",
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = catalogDocument(data)
        val csrf = doc.selectFirst("meta[name=csrf-token]")?.attr("content") ?: ""
        val headers = playerHeaders(csrf)

        doc.select("button.server-btn").amap { btn ->
            val token = btn.attr("data-player-token")
            if (token.isNotBlank()) {
                val response = app.post(
                    "$mainUrl/api/player-url",
                    headers = headers,
                    data = mapOf("t" to token)
                ).parsedSafe<PlayerResponse>()

                response?.let { resolved ->
                    val url = resolved.url
                    when {
                        resolved.type == "mp4" -> callback.invoke(
                            newExtractorLink(name, name, url)
                        )

                        url.startsWith("https://embed69.org/") -> Embed69Extractor.load(
                            url, data, subtitleCallback, callback
                        )

                        url.startsWith("https://xupalace.org/video") -> {
                            val regex = """(go_to_player|go_to_playerVast)\('(.*?)'""".toRegex()
                            regex.findAll(catalogDocument(url).html())
                                .map { it.groupValues[2] }
                                .toList()
                                .amap {
                                    loadExtractor(fixHostsLinks(it), data, subtitleCallback, callback)
                                }
                        }

                        else -> {
                            catalogDocument(url).selectFirst("iframe")?.attr("src")?.let {
                                loadExtractor(fixHostsLinks(it), data, subtitleCallback, callback)
                            }
                        }
                    }
                }
            }
        }
        return true
    }
}

data class PlayerResponse(
    val url: String = "",
    val type: String = "",
)
