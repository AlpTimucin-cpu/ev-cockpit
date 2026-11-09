package com.evcockpit.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import com.evcockpit.data.repository.OsrmRouteRepository
import com.evcockpit.domain.repository.RouteRepository

@Module
@InstallIn(SingletonComponent::class)
abstract class RouteModule {
    @Binds
    @Singleton
    abstract fun bindRouteRepository(impl: OsrmRouteRepository): RouteRepository
}
