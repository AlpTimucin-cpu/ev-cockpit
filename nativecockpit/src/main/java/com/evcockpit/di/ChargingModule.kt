package com.evcockpit.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import com.evcockpit.data.remote.EpdkService
import com.evcockpit.data.repository.EpdkChargingRepository
import com.evcockpit.domain.repository.ChargingRepository
import java.util.concurrent.TimeUnit

@Module
@InstallIn(SingletonComponent::class)
abstract class ChargingModule {
    @Binds @Singleton
    abstract fun chargingRepository(implementation: EpdkChargingRepository): ChargingRepository

    companion object {
        @Provides @Singleton
        fun epdkService(): EpdkService {
            val http=OkHttpClient.Builder()
                .connectTimeout(9,TimeUnit.SECONDS)
                .readTimeout(18,TimeUnit.SECONDS)
                .followRedirects(false)
                .build()
            return Retrofit.Builder()
                .baseUrl("https://raw.githubusercontent.com/")
                .client(http)
                .build().create(EpdkService::class.java)
        }
    }
}
