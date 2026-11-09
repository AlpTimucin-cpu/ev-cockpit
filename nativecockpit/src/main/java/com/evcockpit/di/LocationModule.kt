package com.evcockpit.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import com.evcockpit.data.location.AndroidLocationRepository
import com.evcockpit.domain.repository.LocationRepository

@Module
@InstallIn(SingletonComponent::class)
abstract class LocationModule {
    @Binds @Singleton
    abstract fun bindLocationRepository(repository: AndroidLocationRepository): LocationRepository
}
