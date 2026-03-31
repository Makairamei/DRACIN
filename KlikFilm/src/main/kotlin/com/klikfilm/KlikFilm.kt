package com.klikfilm

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Element
import android.util.Base64
import java.net.URLDecoder

class KlikFilm : MainAPI() {
    override var mainUrl = "https://klikfilm.web.id"
    override var name = "Drama"
    override val hasMainPage = true
    override var lang = "id"
    override val hasSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.AsianDrama,
        TvType.TvSeries,
    )

    // ─────────────────────────────────────────────
    // All 29 platforms. Data = "slug|Display Name"
    // ─────────────────────────────────────────────
    override val mainPage = mainPageOf(
        "bilitv|BiliTV"         to "🎬 BiliTV — Drama Terpopuler",
        "dramabox|DramaBox"     to "📺 DramaBox — Drama Terpopuler",
        "melolo|Melolo"         to "🌸 Melolo — Drama Terpopuler",
        "meloshort|MeloShort"   to "💕 MeloShort — Drama Terpopuler",
        "netshort|NetShort"     to "🔥 NetShort — Drama Terpopuler",
        "dotdrama|DotDrama"     to "⭐ DotDrama — Drama Terpopuler",
        "dramabite|DramaBite"   to "🎯 DramaBite — Drama Terpopuler",
        "shortbox|ShortBox"     to "📦 ShortBox — Drama Terpopuler",
        "flextv|FlexTV"         to "💎 FlexTV — Drama Terpopuler",
        "hishort|HiShort"       to "👋 HiShort — Drama Terpopuler",
        "microdrama|MicroDrama" to "🎭 MicroDrama — Drama Terpopuler",
        "flickreels|FlickReels" to "🎞 FlickReels — Drama Terpopuler",
        "freereels|FreeReels"   to "🆓 FreeReels — Drama Terpopuler",
        "fundrama|Fundrama"     to "😄 Fundrama — Drama Terpopuler",
        "idrama|IDrama"         to "🇮🇩 IDrama — Drama Terpopuler",
        "kalostv|KalosTV"       to "🌟 KalosTV — Drama Terpopuler",
        "mydrama|MyDrama"       to "❤️ MyDrama — Drama Terpopuler",
        "rapidtv|RapidTV"       to "⚡ RapidTV — Drama Terpopuler",
        "reelife|Reelife"       to "🎦 Reelife — Drama Terpopuler",
        "reelshort|ReelShort"   to "🎥 ReelShort — Drama Terpopuler",
        "shortmax|ShortMax"     to "🚀 ShortMax — Drama Terpopuler",
        "shortsky|ShortSky"     to "☁️ ShortSky — Drama Terpopuler",
        "shotshort|ShotShort"   to "🎬 ShotShort — Drama Terpopuler",
        "snackshort|SnackShort" to "🍿 SnackShort — Drama Terpopuler",
        "sodareels|SodaReels"   to "🥤 SodaReels — Drama Terpopuler",
        "stardusttv|StardustTV" to "✨ StardustTV — Drama Terpopuler",
        "starshort|StarShort"   to "⭐ StarShort — Drama Terpopuler",
        "velolo|Velolo"         to "🌈 Velolo — Drama Terpopuler",
        "vigloo|Vigloo"         to "👁 Vigloo — Drama Terpopuler",
        "dramapops|Dramapops"   to "🎊 Dramapops — Drama Terpopuler",
    )

    // Extract platform slug from the mainPage data key "slug|name"
    private fun String.platformSlug() = substringBefore("|")
    private fun String.platformName() = substringAfter("|")

    // ─────────────────────────────────────────────
    // Main Page — scrape the /platform/ listing
    // ─────────────────────────────────────────────
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val slug = request.data.platformSlug()
        // Each platform has a /slug/home page with all dramas listed
        val url = if (page == 1) "$mainUrl/$slug" else "$mainUrl/$slug/home?page=$page"
        val document = app.get(url, headers = baseHeaders()).document

        val items = document.select("a.group").mapNotNull { it.toSearchResult(slug) }
        // If /slug/home has more content, try it on page > 1
        if (page == 1 && items.isEmpty()) {
            val homeDoc = app.get("$mainUrl/$slug/home", headers = baseHeaders()).document
            val homeItems = homeDoc.select("a.group").mapNotNull { it.toSearchResult(slug) }
            return newHomePageResponse(HomePageList(request.name, homeItems, isHorizontalImages = true), hasNext = false)
        }
        return newHomePageResponse(HomePageList(request.name, items, isHorizontalImages = true), hasNext = items.isNotEmpty())
    }

    // ─────────────────────────────────────────────
    // Convert a card element to SearchResponse
    // ─────────────────────────────────────────────
    private fun Element.toSearchResult(platformSlug: String = ""): SearchResponse? {
        val href = this.attr("href").trim()
        if (href.isBlank() || href == "/" || !href.contains("/detail/")) return null

        val fullUrl = fixUrl(href)
        val title = this.selectFirst("p")?.text()?.trim()
            ?: this.attr("title").trim()
            ?: return null
        if (title.isBlank()) return null

        val poster = this.selectFirst("img")?.let {
            it.attr("src").ifBlank { it.attr("data-src") }
        }?.trim()?.let { fixUrlNull(it) }

        val epText = this.selectFirst("div span, span")?.text()?.trim()
        val epCount = epText?.removePrefix("Ep ")?.toIntOrNull()

        return newTvSeriesSearchResponse(title, fullUrl, TvType.AsianDrama) {
            this.posterUrl = poster
            this.episodes = epCount
        }
    }

    // ─────────────────────────────────────────────
    // Search — uses /search?q= endpoint
    // ─────────────────────────────────────────────
    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()

        // Search across klikfilm main search (returns drakor/movie/anime)
        val doc = app.get("$mainUrl/search?q=${query.encodeUri()}", headers = baseHeaders()).document
        doc.select("a.group").forEach { el ->
            val href = el.attr("href").trim()
            if (href.isBlank() || !href.contains("/detail/")) return@forEach
            val title = el.selectFirst("p")?.text()?.trim() ?: return@forEach
            val poster = el.selectFirst("img")?.let {
                it.attr("src").ifBlank { it.attr("data-src") }
            }?.trim()?.let { fixUrlNull(it) }
            results.add(newTvSeriesSearchResponse(title, fixUrl(href), TvType.AsianDrama) {
                this.posterUrl = poster
            })
        }

        // Also search each short-drama platform
        val platforms = listOf(
            "bilitv","dramabox","melolo","meloshort","netshort","dotdrama","dramabite",
            "shortbox","flextv","hishort","microdrama","flickreels","freereels","fundrama",
            "idrama","kalostv","mydrama","rapidtv","reelife","reelshort","shortmax",
            "shortsky","shotshort","snackshort","sodareels","stardusttv","starshort",
            "velolo","vigloo","dramapops"
        )

        for (slug in platforms) {
            try {
                val pDoc = app.get("$mainUrl/$slug/home", headers = baseHeaders()).document
                pDoc.select("a.group").filter { el ->
                    val title = el.selectFirst("p")?.text() ?: return@filter false
                    title.contains(query, ignoreCase = true)
                }.forEach { el ->
                    val href = el.attr("href").trim()
                    if (href.isBlank() || !href.contains("/detail/")) return@forEach
                    val title = el.selectFirst("p")?.text()?.trim() ?: return@forEach
                    val poster = el.selectFirst("img")?.let {
                        it.attr("src").ifBlank { it.attr("data-src") }
                    }?.trim()?.let { fixUrlNull(it) }
                    // avoid duplicates
                    if (results.none { it.url == fixUrl(href) }) {
                        results.add(newTvSeriesSearchResponse(title, fixUrl(href), TvType.AsianDrama) {
                            this.posterUrl = poster
                        })
                    }
                }
            } catch (_: Exception) {}
            // stop if we have enough results
            if (results.size >= 60) break
        }

        return results.distinctBy { it.url }
    }

    // ─────────────────────────────────────────────
    // Load — detail page: title, poster, synopsis, episodes
    // ─────────────────────────────────────────────
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = baseHeaders()).document

        // Extract platform slug from URL: /bilitv/detail/1881 → bilitv
        val slug = url.removePrefix(mainUrl).trimStart('/').substringBefore("/")
        val dramaId = url.substringAfterLast("/")

        val title = document.selectFirst("h1")?.text()?.trim()
            ?: document.title().substringBefore("—").trim()

        val poster = document.selectFirst("img[src*=static], img[src*=image], img[src*=poster]")?.let {
            it.attr("src").ifBlank { it.attr("data-src") }
        }?.trim()?.let { fixUrlNull(it) }
            ?: document.selectFirst("img")?.attr("src")?.let { fixUrlNull(it) }

        val description = document.select("p").firstOrNull { el ->
            val text = el.text().trim()
            text.length > 20 && !text.startsWith("Nonton") && !text.contains("disediakan oleh")
        }?.text()?.trim()
            ?: document.selectFirst("[class*=sinopsis] p, [class*=desc] p, [class*=plot] p")?.text()?.trim()
            ?: "Tidak ada deskripsi tersedia."

        // Episode list — look for episode anchor links /watch/{id}?ep=X
        val episodeLinks = document.select("a[href*='/watch/']")
        val totalEpText = document.select("*").firstOrNull {
            it.text().contains("Episode") && it.text().matches(Regex(".*\\d+\\s*Episode.*"))
        }?.text()

        val episodes: List<Episode>

        if (episodeLinks.isNotEmpty()) {
            episodes = episodeLinks.mapIndexed { idx, a ->
                val epHref = fixUrl(a.attr("href"))
                val epNum = a.attr("href").substringAfterLast("ep=").toIntOrNull() ?: (idx + 1)
                newEpisode(epHref) {
                    this.name = "Episode $epNum"
                    this.episode = epNum
                    this.season = 1
                }
            }.sortedBy { it.episode }
        } else {
            // Fallback: if JS-rendered, construct episodes from total count in page
            val epCount = totalEpText?.filter { it.isDigit() }?.take(4)?.toIntOrNull()
                ?: document.select("*").firstOrNull { it.ownText().matches(Regex("\\d+ TOTAL|\\d+\\s*Total")) }
                    ?.ownText()?.filter { it.isDigit() }?.toIntOrNull()
                ?: 1
            episodes = (1..epCount).map { epNum ->
                newEpisode("$mainUrl/$slug/watch/$dramaId?ep=$epNum") {
                    this.name = "Episode $epNum"
                    this.episode = epNum
                    this.season = 1
                }
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.AsianDrama, episodes) {
            this.posterUrl = poster
            this.plot = description
            this.tags = listOf(slug.replaceFirstChar { it.uppercase() })
            this.showStatus = if (
                document.select("*").any { it.ownText().contains("Berjalan", true) }
            ) ShowStatus.Ongoing else ShowStatus.Completed
        }
    }

    // ─────────────────────────────────────────────
    // loadLinks — extract HLS stream from watch page
    // via klikfilm cors-proxy (double-base64 encoded)
    // ─────────────────────────────────────────────
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data is the watch URL: /slug/watch/id?ep=N
        val doc = app.get(data, headers = baseHeaders()).document

        // ── Strategy 1: find video tag src (rare) ──
        doc.selectFirst("video source, video")?.let { el ->
            val src = el.attr("src").ifBlank { el.attr("data-src") }
            if (src.isNotBlank()) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = src,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.quality = Qualities.Unknown.value
                        this.referer = mainUrl
                    }
                )
                return true
            }
        }

        // ── Strategy 2: find cors-proxy URL in page source ──
        val pageHtml = app.get(data, headers = baseHeaders()).text
        val proxyPattern = Regex("""['"](/api/cors-proxy\?url=[A-Za-z0-9+/=%-]+)['"]""")
        val proxyMatches = proxyPattern.findAll(pageHtml).map { it.groupValues[1] }.toList()

        for (proxiedPath in proxyMatches) {
            val proxiedUrl = "$mainUrl$proxiedPath"
            // Only use the first one (the m3u8 playlist), not individual segments
            val encodedParam = proxiedPath.substringAfter("?url=")
            val directUrl = decodeDoubleBase64(encodedParam)
            if (directUrl != null && directUrl.contains(".m3u8")) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name HLS",
                        url = directUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.quality = Qualities.Unknown.value
                        this.referer = data
                    }
                )
                return true
            } else if (encodedParam.isNotBlank()) {
                // Fallback: use proxied URL directly
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name Proxy",
                        url = proxiedUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.quality = Qualities.Unknown.value
                        this.referer = data
                    }
                )
                return true
            }
        }

        // ── Strategy 3: search for m3u8 in script tags ──
        val m3u8Pattern = Regex("""https?://[^\s'"<>]+\.m3u8[^\s'"<>]*""")
        val scriptMatches = doc.select("script").flatMap { script ->
            m3u8Pattern.findAll(script.data()).map { it.value }.toList()
        }
        for (m3u8Url in scriptMatches) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name HLS",
                    url = m3u8Url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.quality = Qualities.Unknown.value
                    this.referer = data
                }
            )
            return true
        }

        // ── Strategy 4: try iframe sources ──
        doc.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            if (src.isNotBlank()) {
                loadExtractor(httpsify(src), data, subtitleCallback, callback)
            }
        }

        return true
    }

    // ─────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────

    private fun baseHeaders() = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36",
        "Referer" to mainUrl,
        "Accept-Language" to "id-ID,id;q=0.9,en;q=0.8",
    )

    /**
     * The cors-proxy uses double base64 encoding:
     * URL param → base64 → base64 → URL encoded
     * So we need to: URL-decode → base64decode → base64decode → final URL
     */
    private fun decodeDoubleBase64(encoded: String): String? {
        return try {
            val urlDecoded = URLDecoder.decode(encoded, "UTF-8")
            val firstDecode = String(Base64.decode(urlDecoded, Base64.DEFAULT), Charsets.UTF_8)
            val secondDecode = String(Base64.decode(firstDecode, Base64.DEFAULT), Charsets.UTF_8)
            if (secondDecode.startsWith("http")) secondDecode else null
        } catch (_: Exception) {
            try {
                val firstDecode = String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
                val secondDecode = String(Base64.decode(firstDecode, Base64.DEFAULT), Charsets.UTF_8)
                if (secondDecode.startsWith("http")) secondDecode else null
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun String.encodeUri(): String = java.net.URLEncoder.encode(this, "UTF-8")
}
