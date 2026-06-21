package dev.leonardo.ocremotev2.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.leonardo.ocremotev2.data.repository.AgentRepositoryImpl
import dev.leonardo.ocremotev2.data.repository.DraftDataStore
import dev.leonardo.ocremotev2.data.repository.FileRepositoryImpl
import dev.leonardo.ocremotev2.data.repository.ServerRepositoryImpl
import dev.leonardo.ocremotev2.data.repository.McpRepositoryImpl
import dev.leonardo.ocremotev2.data.repository.SettingsRepositoryImpl
import dev.leonardo.ocremotev2.data.repository.TerminalRepositoryImpl
import dev.leonardo.ocremotev2.data.repository.VcsRepositoryImpl
import dev.leonardo.ocremotev2.domain.repository.AgentRepository
import dev.leonardo.ocremotev2.domain.repository.DraftRepository
import dev.leonardo.ocremotev2.domain.repository.FileRepository
import dev.leonardo.ocremotev2.domain.repository.LocalServerRepository
import dev.leonardo.ocremotev2.domain.repository.McpRepository
import dev.leonardo.ocremotev2.domain.repository.ProviderRepository
import dev.leonardo.ocremotev2.domain.repository.ServerConfigRepository
import dev.leonardo.ocremotev2.domain.repository.ServerConnectionRepository
import dev.leonardo.ocremotev2.domain.repository.ServerRepository
import dev.leonardo.ocremotev2.domain.repository.SettingsRepository
import dev.leonardo.ocremotev2.domain.repository.TerminalRepository
import dev.leonardo.ocremotev2.domain.repository.VcsRepository

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

    @Binds
    abstract fun bindMcpRepository(impl: McpRepositoryImpl): McpRepository

    @Binds
    abstract fun bindFileRepository(impl: FileRepositoryImpl): FileRepository

    @Binds
    abstract fun bindVcsRepository(impl: VcsRepositoryImpl): VcsRepository

}
