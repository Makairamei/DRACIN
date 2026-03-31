package com.klikfilm

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element
import android.util.Base64
import java.net.URLDecoder
import java.net.URLEncoder
import com.fasterxml.jackson.annotation.JsonProperty

class KlikFilm : MainAPI() {

    data class SectionDrama(
        @JsonProperty("id") val id: String = "",
        @JsonProperty("title") val title: String = "",
        @JsonProperty("poster") val poster: String = ""
    )

    data class SectionResponse(
        @JsonProperty("dramas") val dramas: List<SectionDrama> = emptyList()
    )

    override var mainUrl = "https://klikfilm.web.id"
    override var name = "Drama"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(
        TvType.AsianDrama,
        TvType.TvSeries,
    )

    // ──────────────────────────────────────────────────────────────────
    // 29 platforms. Data key format: "slug|PlatformName"
    // ──────────────────────────────────────────────────────────────────
    override val mainPage = mainPageOf(
        "bilitv|BiliTV"         to "BiliTV",
        "dramabox|DramaBox"     to "DramaBox",
        "melolo|Melolo"         to "Melolo",
        "meloshort|MeloShort"   to "MeloShort",
        "netshort|NetShort"     to "NetShort",
        "dotdrama|DotDrama"     to "DotDrama",
        "dramabite|DramaBite"   to "DramaBite",
        "shortbox|ShortBox"     to "ShortBox",
        "flextv|FlexTV"         to "FlexTV",
        "hishort|HiShort"       to "HiShort",
        "microdrama|MicroDrama" to "MicroDrama",
        "flickreels|FlickReels" to "FlickReels",
        "freereels|FreeReels"   to "FreeReels",
        "fundrama|Fundrama"     to "Fundrama",
        "idrama|IDrama"         to "IDrama",
        "kalostv|KalosTV"       to "KalosTV",
        "mydrama|MyDrama"       to "MyDrama",
        "rapidtv|RapidTV"       to "RapidTV",
        "reelife|Reelife"       to "Reelife",
        "reelshort|ReelShort"   to "ReelShort",
        "shortmax|ShortMax"     to "ShortMax",
        "shortsky|ShortSky"     to "ShortSky",
        "shotshort|ShotShort"   to "ShotShort",
        "snackshort|SnackShort" to "SnackShort",
        "sodareels|SodaReels"   to "SodaReels",
        "stardusttv|StardustTV" to "StardustTV",
        "starshort|StarShort"   to "StarShort",
        "velolo|Velolo"         to "Velolo",
        "vigloo|Vigloo"         to "Vigloo",
        "dramapops|Dramapops"   to "Dramapops",
    )

    private fun String.platformSlug() = this.substringBefore("|")

    // ──────────────────────────────────────────────────────────────────
    // Main Page
    // ──────────────────────────────────────────────────────────────────
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val slug = request.data.platformSlug()
        
        try {
            val apiUrl = "$mainUrl/api/klikfilm/shortdrama/section?platform=$slug&section=foryou&page=$page"
            val response = app.get(apiUrl, headers = baseHeaders()).parsedSafe<SectionResponse>()
            if (response != null && response.dramas.isNotEmpty()) {
                val items = response.dramas.mapNotNull { drama ->
                    if (drama.id.isBlank() || drama.title.isBlank()) return@mapNotNull null
                    val fullUrl = "$mainUrl/$slug/detail/${drama.id}"
                    newTvSeriesSearchResponse(drama.title, fullUrl, TvType.AsianDrama) {
                        this.posterUrl = drama.poster
                    }
                }
                return newHomePageResponse(
                    HomePageList(request.name, items, isHorizontalImages = false),
                    hasNext = items.isNotEmpty()
                )
            }
        } catch (e: Exception) {
            // ignore API errors and fallback
        }

        val url = if (page == 1) "$mainUrl/$slug" else "$mainUrl/$slug/home?page=$page"
        val document = app.get(url, headers = baseHeaders()).document

        var items = document.select("a.group").mapNotNull { toSearchResult(it) }

        if (page == 1 && items.isEmpty()) {
            val homeDoc = app.get("$mainUrl/$slug/home", headers = baseHeaders()).document
            items = homeDoc.select("a.group").mapNotNull { toSearchResult(it) }
            return newHomePageResponse(
                HomePageList(request.name, items, isHorizontalImages = false),
                hasNext = false
            )
        }
        return newHomePageResponse(
            HomePageList(request.name, items, isHorizontalImages = false),
            hasNext = items.isNotEmpty() && page == 1
        )
    }

    // ──────────────────────────────────────────────────────────────────
    // Card parsing
    // ──────────────────────────────────────────────────────────────────
    private fun toSearchResult(element: Element): SearchResponse? {
        val href = element.attr("href").trim()
        if (href.isBlank() || href == "/" || !href.contains("/detail/")) return null

        val fullUrl = fixUrl(href)
        val title = element.selectFirst("p")?.text()?.trim()
            ?: element.attr("title").trim()
        if (title.isBlank()) return null

        val poster = element.selectFirst("img")?.let { img ->
            val src = img.attr("src")
            if (src.isNotBlank()) src else img.attr("data-src")
        }?.trim()?.let { fixUrlNull(it) }

        val epText = element.select("div, span").firstOrNull { el ->
            el.ownText().contains("Ep", ignoreCase = true)
        }?.ownText()?.trim()
        val epCount = epText?.replace(Regex("[^0-9]"), "")?.toIntOrNull()

        return newTvSeriesSearchResponse(title, fullUrl, TvType.AsianDrama) {
            this.posterUrl = poster
            this.episodes = epCount
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Search
    // ──────────────────────────────────────────────────────────────────
    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val encodedQuery = URLEncoder.encode(query, "UTF-8")

        // Search the main klikfilm search page
        try {
            val doc = app.get("$mainUrl/search?q=$encodedQuery", headers = baseHeaders()).document
            doc.select("a.group").forEach { el ->
                val href = el.attr("href").trim()
                if (href.isBlank() || !href.contains("/detail/")) return@forEach
                val title = el.selectFirst("p")?.text()?.trim() ?: return@forEach
                val poster = el.selectFirst("img")?.let { img ->
                    val src = img.attr("src")
                    if (src.isNotBlank()) src else img.attr("data-src")
                }?.trim()?.let { fixUrlNull(it) }
                results.add(newTvSeriesSearchResponse(title, fixUrl(href), TvType.AsianDrama) {
                    this.posterUrl = poster
                })
            }
        } catch (e: Exception) {
            // ignore
        }

        // Also search through each platform's home listing
        val platforms = listOf(
            "bilitv", "dramabox", "melolo", "meloshort", "netshort", "dotdrama", "dramabite",
            "shortbox", "flextv", "hishort", "microdrama", "flickreels", "freereels", "fundrama",
            "idrama", "kalostv", "mydrama", "rapidtv", "reelife", "reelshort", "shortmax",
            "shortsky", "shotshort", "snackshort", "sodareels", "stardusttv", "starshort",
            "velolo", "vigloo", "dramapops"
        )

        for (slug in platforms) {
            if (results.size >= 60) break
            try {
                val pDoc = app.get("$mainUrl/$slug/home", headers = baseHeaders()).document
                val cards = pDoc.select("a.group").filter { el ->
                    val t = el.selectFirst("p")?.text() ?: return@filter false
                    t.contains(query, ignoreCase = true)
                }
                for (el in cards) {
                    val href = el.attr("href").trim()
                    if (href.isBlank() || !href.contains("/detail/")) continue
                    val title = el.selectFirst("p")?.text()?.trim() ?: continue
                    val poster = el.selectFirst("img")?.let { img ->
                        val src = img.attr("src")
                        if (src.isNotBlank()) src else img.attr("data-src")
                    }?.trim()?.let { fixUrlNull(it) }
                    val fullUrl = fixUrl(href)
                    if (results.none { it.url == fullUrl }) {
                        results.add(newTvSeriesSearchResponse(title, fullUrl, TvType.AsianDrama) {
                            this.posterUrl = poster
                        })
                    }
                }
            } catch (e: Exception) {
                // ignore errors for individual platforms
            }
        }

        return results.distinctBy { it.url }
    }

    // ──────────────────────────────────────────────────────────────────
    // Load detail page
    // ──────────────────────────────────────────────────────────────────
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = baseHeaders()).document

        // Extract platform slug from URL path: e.g. /bilitv/detail/1881 → bilitv
        val pathParts = url.removePrefix(mainUrl).trimStart('/').split("/")
        val slug = pathParts.getOrNull(0) ?: ""
        val dramaId = pathParts.lastOrNull() ?: ""

        val title = document.selectFirst("h1")?.text()?.trim()
            ?: document.title().substringBefore("—").trim()

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.select("img").firstOrNull { img ->
                val src = img.attr("src")
                src.contains("static") || src.contains("image") || src.contains("poster") || src.contains("drama")
            }?.let { img ->
                val src = img.attr("src")
                if (src.isNotBlank()) src else img.attr("data-src")
            }?.trim()?.let { fixUrlNull(it) }
            ?: document.selectFirst("img")?.attr("src")?.let { fixUrlNull(it) }

        val description = document.select("p").firstOrNull { el ->
            val text = el.text().trim()
            text.length > 20 &&
                !text.startsWith("Nonton") &&
                !text.contains("disediakan oleh") &&
                !text.contains("terlengkap")
        }?.text()?.trim() ?: "Tidak ada deskripsi tersedia."

        // Episode list from detail page anchor tags
        val episodeLinks = document.select("a[href*='/watch/']")

        val episodes: List<Episode> = if (episodeLinks.isNotEmpty()) {
            episodeLinks.mapIndexed { idx, a ->
                val epHref = fixUrl(a.attr("href"))
                val epNum = a.attr("href").substringAfterLast("ep=").toIntOrNull() ?: (idx + 1)
                newEpisode(epHref) {
                    this.name = "Episode $epNum"
                    this.episode = epNum
                    this.season = 1
                }
            }.sortedBy { it.episode }.distinctBy { it.episode }
        } else {
            // Fallback: build episode list from total count shown in page
            val epCountText = document.body().text()
            val epCount = Regex("(\\d+)\\s*Episode").find(epCountText)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("(\\d+)\\s*TOTAL", RegexOption.IGNORE_CASE).find(epCountText)?.groupValues?.get(1)?.toIntOrNull()
                ?: 1
            (1..epCount).map { epNum ->
                newEpisode("$mainUrl/$slug/watch/$dramaId?ep=$epNum") {
                    this.name = "Episode $epNum"
                    this.episode = epNum
                    this.season = 1
                }
            }
        }

        val isOngoing = document.body().text().contains("Berjalan", ignoreCase = true)

        return newTvSeriesLoadResponse(title, url, TvType.AsianDrama, episodes) {
            this.posterUrl = poster
            this.plot = description
            this.tags = if (slug.isNotBlank()) listOf(slug.replaceFirstChar { it.uppercase() }) else null
            this.showStatus = if (isOngoing) ShowStatus.Ongoing else ShowStatus.Completed
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Load video links from watch page
    // ──────────────────────────────────────────────────────────────────
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageHtml = app.get(data, headers = baseHeaders()).text

        // Strategy 1: find direct m3u8 URLs in page HTML
        val m3u8DirectPattern = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""")
        val m3u8Matches = m3u8DirectPattern.findAll(pageHtml).map { it.value }.toList()
        if (m3u8Matches.isNotEmpty()) {
            for (m3u8Url in m3u8Matches) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = m3u8Url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.quality = Qualities.Unknown.value
                        this.referer = data
                        this.headers = baseHeaders()
                    }
                )
            }
            return true
        }

        // Strategy 2: find cors-proxy URLs in HTML, decode to get real m3u8
        val proxyUrlPattern = Regex("""['"](/api/cors-proxy\?url=([A-Za-z0-9+/=%]+))['"]""")
        val proxyMatches = proxyUrlPattern.findAll(pageHtml).toList()

        for (match in proxyMatches) {
            val proxiedPath = match.groupValues[1]
            val encodedParam = match.groupValues[2]
            
            // 1. Emit the RAW proxy link as Server 1
            val proxiedFullUrl = "$mainUrl$proxiedPath"
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name Proxy",
                    url = proxiedFullUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.quality = Qualities.Unknown.value
                    this.referer = data
                    this.headers = baseHeaders()
                }
            )

            // 2. Try Decoding and emit direct HLS link as Server 2
            val decodedUrl = decodeDoubleBase64(encodedParam)
            if (decodedUrl != null && decodedUrl.startsWith("http") && decodedUrl.contains(".m3u8")) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name HLS",
                        url = decodedUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.quality = Qualities.Unknown.value
                        this.referer = data
                        this.headers = baseHeaders()
                    }
                )
            }
        }

        // Strategy 3: iframe fallback
        val doc = app.get(data, headers = baseHeaders()).document
        doc.select("iframe").forEach { iframe ->
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            if (src.isNotBlank() && src.startsWith("http")) {
                loadExtractor(src, data, subtitleCallback, callback)
            }
        }

        return true
    }

    // ──────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────

    private fun baseHeaders(): Map<String, String> = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
        "Referer" to "$mainUrl/",
        "Accept-Language" to "id-ID,id;q=0.9,en;q=0.8",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
    )

    /**
     * klikfilm cors-proxy uses double base64:
     * real URL → base64 → base64 → URL-encode → query param
     * So we: URL-decode → base64-decode → base64-decode → real URL
     */
    private fun decodeDoubleBase64(encoded: String): String? {
        return try {
            val step1 = URLDecoder.decode(encoded, "UTF-8")
            val step2Bytes = Base64.decode(step1, Base64.DEFAULT or Base64.URL_SAFE)
            val step2 = String(step2Bytes, Charsets.UTF_8)
            
            // Sometimes it's a single encoding
            if (step2.startsWith("http")) return step2
            
            val step3Bytes = Base64.decode(step2, Base64.DEFAULT or Base64.URL_SAFE)
            val step3 = String(step3Bytes, Charsets.UTF_8)
            if (step3.startsWith("http")) step3 else null
        } catch (e: Exception) {
            try {
                val step2Bytes = Base64.decode(encoded, Base64.DEFAULT or Base64.URL_SAFE)
                val step2 = String(step2Bytes, Charsets.UTF_8)
                if (step2.startsWith("http")) return step2
                
                val step3Bytes = Base64.decode(step2, Base64.DEFAULT or Base64.URL_SAFE)
                val step3 = String(step3Bytes, Charsets.UTF_8)
                if (step3.startsWith("http")) step3 else null
            } catch (e2: Exception) {
                null
            }
        }
    }
}
