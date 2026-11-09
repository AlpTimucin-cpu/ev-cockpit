package com.evcockpit.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import com.evcockpit.data.repository.Elm327VehicleRepository
import com.evcockpit.domain.repository.VehicleRepository
import com.evcockpit.domain.repository.ObdConnectionController

@Module
@InstallIn(SingletonComponent::class)
abstract class VehicleModule {
    @Binds @Singleton
    abstract fun bindVehicleRepository(implementation: Elm327VehicleRepository): VehicleRepository
    @Binds @Singleton
    abstract fun bindObdController(implementation: Elm327VehicleRepository): ObdConnectionController
}
