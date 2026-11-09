package com.evcockpit.data.remote

import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Streaming

/** Date-stamped, gzip snapshot generated from EPDK's published public records.
 * Do not use undocumented live availability endpoints or private credentials.
 */
interface EpdkService {
    @Streaming
    @GET("AlpTimucin-cpu/ev-cockpit/feature/ev-cockpit/data/charging/epdk_public_stations.json.gz")
    suspend fun getPublicStations(): ResponseBody
}
