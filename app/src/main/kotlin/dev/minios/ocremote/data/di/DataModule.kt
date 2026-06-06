package dev.minios.ocremote.data.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.minios.ocremote.data.repository.ChatRepositoryImpl
import dev.minios.ocremote.data.repository.SessionRepositoryImpl
import dev.minios.ocremote.domain.repository.ChatRepository
import dev.minios.ocremote.domain.repository.SessionRepository

/**
 * Hilt module that binds Chat and Session domain interfaces to their Data-layer implementations.
 * Server and Settings bindings live in di/DomainModule.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {

    @Binds
    abstract fun bindChatRepository(impl: ChatRepositoryImpl): ChatRepository

    @Binds
    abstract fun bindSessionRepository(impl: SessionRepositoryImpl): SessionRepository
}
