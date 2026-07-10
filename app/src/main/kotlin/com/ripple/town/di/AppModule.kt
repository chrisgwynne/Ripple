package com.ripple.town.di

import android.content.Context
import androidx.room.Room
import com.ripple.town.core.database.RippleDatabase
import com.ripple.town.core.simulation.providers.CloudSaveRepository
import com.ripple.town.core.simulation.providers.DialogueProvider
import com.ripple.town.core.simulation.providers.ExternalWorldEventProvider
import com.ripple.town.core.simulation.providers.NarrativeTextProvider
import com.ripple.town.core.simulation.providers.NoOpCloudSaveRepository
import com.ripple.town.core.simulation.providers.NoOpDialogueProvider
import com.ripple.town.core.simulation.providers.NoOpExternalWorldEventProvider
import com.ripple.town.core.simulation.providers.NoOpNarrativeTextProvider
import com.ripple.town.core.simulation.providers.NoOpWorldPressureMapper
import com.ripple.town.core.simulation.providers.WorldPressureMapper
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): RippleDatabase =
        Room.databaseBuilder(context, RippleDatabase::class.java, RippleDatabase.NAME)
            .fallbackToDestructiveMigrationOnDowngrade()
            .build()

    @Provides
    @Singleton
    fun provideAppScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Future-architecture seams: local no-ops for the prototype.

    @Provides
    @Singleton
    fun provideExternalWorldEventProvider(): ExternalWorldEventProvider = NoOpExternalWorldEventProvider()

    @Provides
    @Singleton
    fun provideNarrativeTextProvider(): NarrativeTextProvider = NoOpNarrativeTextProvider()

    @Provides
    @Singleton
    fun provideDialogueProvider(): DialogueProvider = NoOpDialogueProvider()

    @Provides
    @Singleton
    fun provideWorldPressureMapper(): WorldPressureMapper = NoOpWorldPressureMapper()

    @Provides
    @Singleton
    fun provideCloudSaveRepository(): CloudSaveRepository = NoOpCloudSaveRepository()
}
