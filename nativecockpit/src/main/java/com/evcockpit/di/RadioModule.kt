package com.evcockpit.di

import com.evcockpit.data.radio.RadioBrowserApi
import com.evcockpit.data.radio.RadioStation
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

@Module
@InstallIn(SingletonComponent::class)
object RadioModule {
    @Provides
    @Singleton
    fun radioApi(): RadioBrowserApi {
        val client = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(18, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder()
                    .header("User-Agent", "EV-Cockpit/1.0 (Android; radio directory)")
                    .build())
            }
            .build()

        // Radio Browser can return a temporarily unavailable server.
        val primary = Retrofit.Builder()
            .baseUrl("https://all.api.radio-browser.info/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build().create(RadioBrowserApi::class.java)
        val alternate = Retrofit.Builder()
            .baseUrl("https://de1.api.radio-browser.info/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build().create(RadioBrowserApi::class.java)

        return object : RadioBrowserApi {
            override suspend fun stations(
                hideBroken: Boolean,
                order: String,
                reverse: Boolean,
                limit: Int,
                onlyHttps: Boolean
            ): List<RadioStation> {
                var last: Exception? = null
                for (server in listOf(primary, alternate)) {
                    try {
                        return server.stations(
                            hideBroken = hideBroken, order = order,
                            reverse = reverse, limit = limit,
                            onlyHttps = onlyHttps
                        )
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (error: Exception) {
                        last = error
                    }
                }
                throw last ?: IOException("Radio Browser sunucularına ulaşılamadı")
            }
        }
    }
}
