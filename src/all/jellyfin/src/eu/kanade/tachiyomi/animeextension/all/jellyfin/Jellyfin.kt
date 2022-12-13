package eu.kanade.tachiyomi.animeextension.all.jellyfin

import android.app.Application
import android.content.SharedPreferences
import android.text.InputType
import android.util.Log
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class Jellyfin : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "Jellyfin"

    override val lang = "all"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override val baseUrl = JFConstants.getPrefHostUrl(preferences)

    private val username = JFConstants.getPrefUsername(preferences)
    private val password = JFConstants.getPrefPassword(preferences)
    private val apiKey: String
    private val userId: String

    init {
        val key = JFConstants.getPrefApiKey(preferences)
        val uid = JFConstants.getPrefUserId(preferences)
        if (key == null || uid == null) {
            val (newKey, newUid) = JellyfinAuthenticator(preferences, baseUrl, client)
                .login(username, password)
            Log.e("bruh", "$newKey, $newUid")
            apiKey = newKey ?: ""
            userId = newUid ?: ""
        } else {
            apiKey = key
            userId = uid
        }
    }

    // Popular Anime

    override fun popularAnimeRequest(page: Int): Request {
        val parentId = preferences.getString(JFConstants.MEDIALIB_KEY, "")
        if (parentId.isNullOrEmpty()) {
            throw Exception("Select library in the extension settings.")
        }
        val startIndex = (page - 1) * 20

        val url = "$baseUrl/Users/$userId/Items".toHttpUrlOrNull()!!.newBuilder()

        url.addQueryParameter("api_key", apiKey)
        url.addQueryParameter("StartIndex", startIndex.toString())
        url.addQueryParameter("Limit", "20")
        url.addQueryParameter("Recursive", "true")
        url.addQueryParameter("SortBy", "SortName")
        url.addQueryParameter("SortOrder", "Ascending")
        url.addQueryParameter("includeItemTypes", "Movie,Series,Season")
        url.addQueryParameter("ImageTypeLimit", "1")
        url.addQueryParameter("ParentId", parentId)
        url.addQueryParameter("EnableImageTypes", "Primary")

        return GET(url.toString())
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val (animesList, hasNextPage) = animeParse(response)

        // Currently sorts by name
        animesList.sortBy { it.title }

        return AnimesPage(animesList, hasNextPage)
    }

    // Episodes

    override fun episodeListParse(response: Response): List<SEpisode> {
        val json = response.body?.let { Json.decodeFromString<JsonObject>(it.string()) }

        val episodeList = mutableListOf<SEpisode>()

        // Is movie
        if (json!!.containsKey("Type")) {
            val episode = SEpisode.create()
            val id = json["Id"]!!.jsonPrimitive.content

            episode.episode_number = 1.0F
            episode.name = json["Name"]!!.jsonPrimitive.content

            episode.setUrlWithoutDomain("/Users/$userId/Items/$id?api_key=$apiKey")
            episodeList.add(episode)
        } else {
            val items = json["Items"]!!

            for (item in 0 until items.jsonArray.size) {

                val episode = SEpisode.create()
                val jsonObj = JsonObject(items.jsonArray[item].jsonObject)

                val id = jsonObj["Id"]!!.jsonPrimitive.content

                if (jsonObj["IndexNumber"] == null) {
                    episode.episode_number = 0.0F
                } else {
                    episode.episode_number = jsonObj["IndexNumber"]!!.jsonPrimitive.float
                }
                episode.name = jsonObj["Name"]!!.jsonPrimitive.content

                episode.setUrlWithoutDomain("/Users/$userId/Items/$id?api_key=$apiKey")
                episodeList.add(episode)
            }
        }

        return episodeList.reversed()
    }

    private fun animeParse(response: Response): Pair<MutableList<SAnime>, Boolean> {
        val items = response.body?.let { Json.decodeFromString<JsonObject>(it.string()) }?.get("Items")

        val animesList = mutableListOf<SAnime>()

        if (items != null) {
            for (item in 0 until items.jsonArray.size) {
                val anime = SAnime.create()
                val jsonObj = JsonObject(items.jsonArray[item].jsonObject)

                if (jsonObj["Type"]!!.jsonPrimitive.content == "Season") {
                    val seasonId = jsonObj["Id"]!!.jsonPrimitive.content
                    val seriesId = jsonObj["SeriesId"]!!.jsonPrimitive.content

                    anime.setUrlWithoutDomain("/Shows/$seriesId/Episodes?api_key=$apiKey&SeasonId=$seasonId")

                    // Virtual if show doesn't have any sub-folders, i.e. no seasons
                    if (jsonObj["LocationType"]!!.jsonPrimitive.content == "Virtual") {
                        anime.title = jsonObj["SeriesName"]!!.jsonPrimitive.content
                        anime.thumbnail_url = "$baseUrl/Items/$seriesId/Images/Primary?api_key=$apiKey"
                    } else {
                        anime.title = jsonObj["SeriesName"]!!.jsonPrimitive.content + " " + jsonObj["Name"]!!.jsonPrimitive.content
                        anime.thumbnail_url = "$baseUrl/Items/$seasonId/Images/Primary?api_key=$apiKey"
                    }

                    // If season doesn't have image, fallback to series image
                    if (jsonObj["ImageTags"].toString() == "{}") {
                        anime.thumbnail_url = "$baseUrl/Items/$seriesId/Images/Primary?api_key=$apiKey"
                    }
                } else if (jsonObj["Type"]!!.jsonPrimitive.content == "Movie") {
                    val id = jsonObj["Id"]!!.jsonPrimitive.content

                    anime.title = jsonObj["Name"]!!.jsonPrimitive.content
                    anime.thumbnail_url = "$baseUrl/Items/$id/Images/Primary?api_key=$apiKey"

                    anime.setUrlWithoutDomain("/Users/$userId/Items/$id?api_key=$apiKey")
                } else {
                    continue
                }

                animesList.add(anime)
            }
        }

        val hasNextPage = animesList.size >= 20
        return Pair(animesList, hasNextPage)
    }

    // Video urls

    override fun videoListParse(response: Response): List<Video> {
        val videoList = mutableListOf<Video>()
        val item = response.body?.let { Json.decodeFromString<JsonObject>(it.string()) }
        val id = item?.get("Id")!!.jsonPrimitive.content

        val sessionResponse = client.newCall(
            GET(
                "$baseUrl/Items/$id/PlaybackInfo?userId=$userId&api_key=$apiKey"
            )
        ).execute()
        val sessionJson = sessionResponse.body?.let { Json.decodeFromString<JsonObject>(it.string()) }
        val sessionId = sessionJson?.get("PlaySessionId")!!.jsonPrimitive.content
        val mediaStreams = sessionJson["MediaSources"]!!.jsonArray[0].jsonObject["MediaStreams"]?.jsonArray

        val subtitleList = mutableListOf<Track>()

        val prefSub = preferences.getString(JFConstants.PREF_SUB_KEY, "eng")
        val prefAudio = preferences.getString(JFConstants.PREF_AUDIO_KEY, "jpn")

        var audioIndex = 1
        var width = 1920
        var height = 1080

        // Get subtitle streams and audio index
        if (mediaStreams != null) {
            for (media in mediaStreams) {
                if (media.jsonObject["Type"]!!.jsonPrimitive.content == "Subtitle") {
                    val subUrl = "$baseUrl/Videos/$id/$id/Subtitles/${media.jsonObject["Index"]!!.jsonPrimitive.int}/0/Stream.${media.jsonObject["Codec"]!!.jsonPrimitive.content}?api_key=$apiKey"
                    // TODO: add ttf files in media attachment (if possible)
                    val lang = media.jsonObject["Language"]
                    if (lang != null) {
                        if (lang.jsonPrimitive.content == prefSub) {
                            subtitleList.add(
                                0,
                                Track(
                                    subUrl,
                                    media.jsonObject["DisplayTitle"]!!.jsonPrimitive.content
                                )
                            )
                        } else {
                            subtitleList.add(
                                Track(
                                    subUrl,
                                    media.jsonObject["DisplayTitle"]!!.jsonPrimitive.content
                                )
                            )
                        }
                    } else {
                        subtitleList.add(
                            Track(subUrl, media.jsonObject["DisplayTitle"]!!.jsonPrimitive.content)
                        )
                    }
                }

                if (media.jsonObject["Type"]!!.jsonPrimitive.content == "Audio") {
                    val lang = media.jsonObject["Language"]
                    if (lang != null) {
                        if (lang.jsonPrimitive.content == prefAudio) {
                            audioIndex = media.jsonObject["Index"]!!.jsonPrimitive.int
                        }
                    }
                }

                if (media.jsonObject["Type"]!!.jsonPrimitive.content == "Video") {
                    width = media.jsonObject["Width"]!!.jsonPrimitive.int
                    height = media.jsonObject["Height"]!!.jsonPrimitive.int
                }
            }
        }

        // Loop over qualities
        for (quality in JFConstants.QUALITIES_LIST) {
            if (width < quality[0] as Int && height < quality[1] as Int) {
                val url = "$baseUrl/Videos/$id/stream?static=True&api_key=$apiKey"
                videoList.add(Video(url, "Best", url))

                return videoList.reversed()
            } else {
                val url = "$baseUrl/videos/$id/main.m3u8".toHttpUrlOrNull()!!.newBuilder()

                url.addQueryParameter("api_key", apiKey)
                url.addQueryParameter("VideoCodec", "h264")
                url.addQueryParameter("AudioCodec", "aac,mp3")
                url.addQueryParameter("AudioStreamIndex", audioIndex.toString())
                url.addQueryParameter("VideoCodec", "h264")
                url.addQueryParameter("VideoCodec", "h264")
                url.addQueryParameter(
                    (quality[2] as Array<*>)[0].toString(), (quality[2] as Array<*>)[1].toString()
                )
                url.addQueryParameter(
                    (quality[2] as Array<*>)[2].toString(), (quality[2] as Array<*>)[3].toString()
                )
                url.addQueryParameter("PlaySessionId", sessionId)
                url.addQueryParameter("TranscodingMaxAudioChannels", "6")
                url.addQueryParameter("RequireAvc", "false")
                url.addQueryParameter("SegmentContainer", "ts")
                url.addQueryParameter("MinSegments", "1")
                url.addQueryParameter("BreakOnNonKeyFrames", "true")
                url.addQueryParameter("h264-profile", "high,main,baseline,constrainedbaseline")
                url.addQueryParameter("h264-level", "51")
                url.addQueryParameter("h264-deinterlace", "true")
                url.addQueryParameter("TranscodeReasons", "VideoCodecNotSupported,AudioCodecNotSupported,ContainerBitrateExceedsLimit")

                videoList.add(Video(url.toString(), quality[3] as String, url.toString(), subtitleTracks = subtitleList))
            }
        }

        val url = "$baseUrl/Videos/$id/stream?static=True&api_key=$apiKey"
        videoList.add(Video(url, "Best", url))

        return videoList.reversed()
    }

    // search

    override fun searchAnimeParse(response: Response): AnimesPage {
        val (animesList, hasNextPage) = animeParse(response)

        // Currently sorts by name
        animesList.sortBy { it.title }

        return AnimesPage(animesList, hasNextPage)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return if (query.isNotBlank()) {
            val searchUrl = "$baseUrl/Users/$userId/Items".toHttpUrlOrNull()!!.newBuilder()

            searchUrl.addQueryParameter("api_key", apiKey)
            searchUrl.addQueryParameter("searchTerm", query)
            searchUrl.addQueryParameter("Limit", "2")
            searchUrl.addQueryParameter("Recursive", "true")
            searchUrl.addQueryParameter("SortBy", "SortName")
            searchUrl.addQueryParameter("SortOrder", "Ascending")
            searchUrl.addQueryParameter("includeItemTypes", "Movie,Series,Season")

            val searchResponse = client.newCall(
                GET(searchUrl.toString())
            ).execute()

            val jsonArr = searchResponse.body?.let {
                Json.decodeFromString<JsonObject>(it.string())
            }?.get("Items")

            if (jsonArr == buildJsonArray { }) {
                throw Exception("No results found")
            }

            val firstItem = jsonArr!!.jsonArray[0]
            val id = firstItem.jsonObject["Id"]!!.jsonPrimitive.content

            val url = "$baseUrl/Users/$userId/Items".toHttpUrlOrNull()!!.newBuilder()

            val startIndex = (page - 1) * 20

            url.addQueryParameter("api_key", apiKey)
            url.addQueryParameter("StartIndex", startIndex.toString())
            url.addQueryParameter("Limit", "20")
            url.addQueryParameter("Recursive", "true")
            url.addQueryParameter("SortBy", "SortName")
            url.addQueryParameter("SortOrder", "Ascending")
            url.addQueryParameter("includeItemTypes", "Movie,Series,Season")
            url.addQueryParameter("ImageTypeLimit", "1")
            url.addQueryParameter("EnableImageTypes", "Primary")
            url.addQueryParameter("ParentId", id)

            GET(url.toString())
        } else {
            throw Exception("No results found")
        }
    }

    // Details

    override fun animeDetailsRequest(anime: SAnime): Request {
        val infoArr = anime.url.split("/").toTypedArray()

        val id = if (infoArr[1] == "Users") {
            infoArr[4].split("?").toTypedArray()[0]
        } else {
            infoArr[2]
        }

        val url = "$baseUrl/Users/$userId/Items/$id".toHttpUrlOrNull()!!.newBuilder()

        url.addQueryParameter("api_key", apiKey)
        url.addQueryParameter("fields", "Studios")

        return GET(url.toString())
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val item = response.body?.let { Json.decodeFromString<JsonObject>(it.string()) }!!.jsonObject

        val anime = SAnime.create()

        anime.author = if (item["Studios"]!!.jsonArray.isEmpty()) {
            ""
        } else {
            item["Studios"]!!.jsonArray[0].jsonObject["Name"]!!.jsonPrimitive.content
        }

        anime.description = item["Overview"]?.let {
            Jsoup.parse(it.jsonPrimitive.content.replace("<br>", "br2n")).text().replace("br2n", "\n")
        } ?: ""

        if (item["Genres"]!!.jsonArray.isEmpty()) {
            anime.genre = ""
        } else {
            val genres = mutableListOf<String>()

            for (genre in 0 until item["Genres"]?.jsonArray?.size!!) {
                genres.add(
                    item["Genres"]?.jsonArray!![genre].jsonPrimitive.content
                )
            }
            anime.genre = genres.joinToString(separator = ", ")
        }

        anime.status = item["Status"]?.let {
            if (it.jsonPrimitive.content == "Ended") SAnime.COMPLETED else SAnime.COMPLETED
        } ?: SAnime.UNKNOWN

        return anime
    }

    // Latest

    override fun latestUpdatesRequest(page: Int): Request {
        val parentId = preferences.getString(JFConstants.MEDIALIB_KEY, "")
        if (parentId.isNullOrEmpty()) {
            throw Exception("Select library in the extension settings.")
        }

        val startIndex = (page - 1) * 20

        val url = "$baseUrl/Users/$userId/Items".toHttpUrlOrNull()!!.newBuilder()

        url.addQueryParameter("api_key", apiKey)
        url.addQueryParameter("StartIndex", startIndex.toString())
        url.addQueryParameter("Limit", "20")
        url.addQueryParameter("Recursive", "true")
        url.addQueryParameter("SortBy", "DateCreated")
        url.addQueryParameter("SortOrder", "Descending")
        url.addQueryParameter("includeItemTypes", "Movie,Series,Season")
        url.addQueryParameter("ImageTypeLimit", "1")
        url.addQueryParameter("ParentId", parentId)
        url.addQueryParameter("EnableImageTypes", "Primary")

        return GET(url.toString())
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val (animesList, hasNextPage) = animeParse(response)
        return AnimesPage(animesList, hasNextPage)
    }

    // Filters - not used

    // settings

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addPreference(
            screen.editTextPreference(
                JFConstants.HOSTURL_KEY, JFConstants.HOSTURL_TITLE, JFConstants.HOSTURL_DEFAULT, baseUrl, false, ""
            )
        )
        screen.addPreference(
            screen.editTextPreference(
                JFConstants.USERNAME_KEY, JFConstants.USERNAME_TITLE, "", username, false, ""
            )
        )
        screen.addPreference(
            screen.editTextPreference(
                JFConstants.PASSWORD_KEY, JFConstants.PASSWORD_TITLE, "", password, true, ""
            )
        )
        val subLangPref = ListPreference(screen.context).apply {
            key = JFConstants.PREF_SUB_KEY
            title = JFConstants.PREF_SUB_TITLE
            entries = JFConstants.PREF_ENTRIES
            entryValues = JFConstants.PREF_VALUES
            setDefaultValue("eng")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(subLangPref)
        val audioLangPref = ListPreference(screen.context).apply {
            key = JFConstants.PREF_AUDIO_KEY
            title = JFConstants.PREF_AUDIO_TITLE
            entries = JFConstants.PREF_ENTRIES
            entryValues = JFConstants.PREF_VALUES
            setDefaultValue("jpn")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(audioLangPref)
        val mediaLibPref = ListPreference(screen.context).apply {
            key = JFConstants.MEDIALIB_KEY
            title = JFConstants.MEDIALIB_TITLE
            summary = "%s"

            if (apiKey == "" || userId == "" || baseUrl == "") {
                this.setEnabled(false)
                this.title = "Please Set Host url, API key, and User first"
            } else {
                this.setEnabled(true)
                this.title = title
            }

            Thread {
                try {
                    val mediaLibsResponse = client.newCall(
                        GET("$baseUrl/Users/$userId/Items?api_key=$apiKey")
                    ).execute()
                    val mediaJson = mediaLibsResponse.body?.let { Json.decodeFromString<JsonObject>(it.string()) }?.get("Items")?.jsonArray

                    val entriesArray = mutableListOf<String>()
                    val entriesValueArray = mutableListOf<String>()

                    if (mediaJson != null) {
                        for (media in mediaJson) {
                            entriesArray.add(media.jsonObject["Name"]!!.jsonPrimitive.content)
                            entriesValueArray.add(media.jsonObject["Id"]!!.jsonPrimitive.content)
                        }
                    }

                    entries = entriesArray.toTypedArray()
                    entryValues = entriesValueArray.toTypedArray()
                } catch (ex: Exception) {
                    entries = emptyArray()
                    entryValues = emptyArray()
                }
            }.start()

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(mediaLibPref)
    }

    private fun PreferenceScreen.editTextPreference(key: String, title: String, default: String, value: String, isPassword: Boolean = false, placeholder: String): EditTextPreference {
        return EditTextPreference(context).apply {
            this.key = key
            this.title = title
            summary = value.ifEmpty { placeholder }
            this.setDefaultValue(default)
            dialogTitle = title

            if (isPassword) {
                setOnBindEditTextListener {
                    it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
            }

            setOnPreferenceChangeListener { _, newValue ->
                try {
                    val res = preferences.edit().putString(title, newValue as String).commit()
                    Toast.makeText(context, "Restart Aniyomi to apply new settings.", Toast.LENGTH_LONG).show()
                    res
                } catch (e: Exception) {
                    Log.e("Jellyfin", "Error setting preference.", e)
                    false
                }
            }
        }
    }
}
