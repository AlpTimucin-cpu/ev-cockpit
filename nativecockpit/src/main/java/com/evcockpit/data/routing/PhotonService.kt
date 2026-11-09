package com.evcockpit.data.routing

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

/** Avoid unsupported Photon lang=tr on public demo instances. */
interface PhotonService {
    @GET("api/")
    suspend fun search(
        @Query("q") query: String,
        @Query("limit") limit: Int = 8,
        @Query("lang") language: String? = null
    ): Response<ResponseBody>
}
