package dev.minios.ocremote.data.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.minios.ocremote.data.repository.ChatRepositoryImpl
import dev.minios.ocremote.data.repository.SessionRepositoryImpl
import dev.minios.ocremote.data.repository.ServerRepositoryImpl
import dev.minios.ocremote.data.repository.SettingsRepositoryImpl
import dev.minios.ocremote.domain.repository.ChatRepository
import dev.minios.ocremote.domain.repository.SessionRepository
import dev.minios.ocremote.domain.repository.ServerRepository
import dev.minios.ocremote.domain.repository.SettingsRepository

/**
 * Hilt module that binds domain Repository interfaces to their Data-layer implementations.
 *
 * Phase 3 introduces ChatRepositoryImpl and SessionRepositoryImpl.
 * ServerRepositoryImpl and SettingsRepositoryImpl wrap existing data-layer classes
 * and adapt them to the clean domain interfaces.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {

    @Binds
    abstract fun bindChatRepository(impl: ChatRepositoryImpl): ChatRepository

    @Binds
    abstract fun bindSessionRepository(impl: SessionRepositoryImpl): SessionRepository

    @Binds
    abstract fun bindServerRepository(impl: ServerRepositoryImpl): ServerRepository

    @Binds
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository
}
