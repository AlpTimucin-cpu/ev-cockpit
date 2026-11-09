package com.evcockpit.data.radio

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET
import retrofit2.http.Query

/** Radio Browser's JSON fields; blank values are rejected in RadioRepository. */
data class RadioStation(
    @SerializedName("stationuuid") val id: String = "",
    val name: String = "",
    @SerializedName("url_resolved") val urlResolved: String = "",
    val favicon: String = "",
    val tags: String = "",
    val votes: Int = 0,
    @SerializedName("clickcount") val clickCount: Int = 0
)

interface RadioBrowserApi {
    // Request Turkish stations by ISO country code; top 50 by listen/click count.
    @GET("json/stations/search?countrycode=TR")
    suspend fun stations(
        @Query("hidebroken") hideBroken: Boolean = true,
        @Query("order") order: String = "clickcount",
        @Query("reverse") reverse: Boolean = true,
        @Query("limit") limit: Int = 50,
        // Server-side HTTPS-only filtering prevents the "50 HTTP / 0 playable" case.
        @Query("is_https") onlyHttps: Boolean = true
    ): List<RadioStation>
}
