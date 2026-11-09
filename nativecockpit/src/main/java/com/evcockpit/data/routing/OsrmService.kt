package com.evcockpit.data.routing

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Url

/** Fully validated URL. Retrofit does not escape the OSRM waypoint semicolon. */
interface OsrmService {
    @GET
    suspend fun route(@Url url: String): Response<ResponseBody>
}
