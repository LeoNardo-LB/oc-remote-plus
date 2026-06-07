package dev.minios.ocremote.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.minios.ocremote.data.repository.AgentRepositoryImpl
import dev.minios.ocremote.data.repository.DraftDataStore
import dev.minios.ocremote.data.repository.ServerRepositoryImpl
import dev.minios.ocremote.data.repository.SettingsRepositoryImpl
import dev.minios.ocremote.data.repository.TerminalRepositoryImpl
import dev.minios.ocremote.domain.repository.AgentRepository
import dev.minios.ocremote.domain.repository.DraftRepository
import dev.minios.ocremote.domain.repository.LocalServerRepository
import dev.minios.ocremote.domain.repository.ProviderRepository
import dev.minios.ocremote.domain.repository.ServerConfigRepository
import dev.minios.ocremote.domain.repository.ServerConnectionRepository
import dev.minios.ocremote.domain.repository.ServerRepository
import dev.minios.ocremote.domain.repository.SettingsRepository
import dev.minios.ocremote.domain.repository.TerminalRepository

@Module
@InstallIn(SingletonComponent::class)
abstract class DomainModule {

    @Binds
    abstract fun bindDraftRepository(impl: DraftDataStore): DraftRepository

    @Binds
    abstract fun bindTerminalRepository(impl: TerminalRepositoryImpl): TerminalRepository

    @Binds
    abstract fun bindAgentRepository(impl: AgentRepositoryImpl): AgentRepository

    @Binds
    abstract fun bindServerRepository(impl: ServerRepositoryImpl): ServerRepository

    @Binds
    abstract fun bindServerConfigRepository(impl: ServerRepositoryImpl): ServerConfigRepository

    @Binds
    abstract fun bindServerConnectionRepository(impl: ServerRepositoryImpl): ServerConnectionRepository

    @Binds
    abstract fun bindLocalServerRepository(impl: ServerRepositoryImpl): LocalServerRepository

    @Binds
    abstract fun bindProviderRepository(impl: ServerRepositoryImpl): ProviderRepository

    @Binds
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository

}
