package com.ripple.town.di

import android.content.Context
import androidx.room.Room
import com.ripple.town.core.database.RippleDatabase
import com.ripple.town.core.simulation.providers.CloudSaveRepository
import com.ripple.town.core.simulation.providers.DialogueProvider
import com.ripple.town.core.simulation.providers.ExternalWorldEventProvider
import com.ripple.town.core.simulation.providers.NarrativeTextProvider
import com.ripple.town.core.simulation.providers.NoOpCloudSaveRepository
import com.ripple.town.core.simulation.providers.NoOpExternalWorldEventProvider
import com.ripple.town.core.simulation.providers.NoOpWorldPressureMapper
import com.ripple.town.core.simulation.providers.TemplateDialogueProvider
import com.ripple.town.core.simulation.providers.TemplateNarrativeTextProvider
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

    // Phase 4 backlog item: NarrativeTextProvider/DialogueProvider. The template-based
    // implementations are now the real default (see providers/TemplateProviders.kt) — no LLM
    // call, no network, deterministic. NoOpNarrativeTextProvider/NoOpDialogueProvider remain in
    // FutureProviders.kt for tests/fallback but are no longer bound here. Swapping in a real
    // LLM-backed provider later (needs an API key/budget/model choice — a separate, still-open
    // decision) only means changing what these two functions return; every call site depends on
    // the interface only.

    @Provides
    @Singleton
    fun provideNarrativeTextProvider(): NarrativeTextProvider = TemplateNarrativeTextProvider()

    @Provides
    @Singleton
    fun provideDialogueProvider(): DialogueProvider = TemplateDialogueProvider()

    @Provides
    @Singleton
    fun provideWorldPressureMapper(): WorldPressureMapper = NoOpWorldPressureMapper()

    @Provides
    @Singleton
    fun provideCloudSaveRepository(): CloudSaveRepository = NoOpCloudSaveRepository()
}
