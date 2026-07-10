package dev.leonardo.ocremotev2.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import dev.leonardo.ocremotev2.data.di.DataModule
import dev.leonardo.ocremotev2.domain.repository.AgentRepository
import dev.leonardo.ocremotev2.domain.repository.ChatRepository
import dev.leonardo.ocremotev2.domain.repository.DraftRepository
import dev.leonardo.ocremotev2.domain.repository.FileRepository
import dev.leonardo.ocremotev2.domain.repository.LocalServerRepository
import dev.leonardo.ocremotev2.domain.repository.McpRepository
import dev.leonardo.ocremotev2.domain.repository.ProviderRepository
import dev.leonardo.ocremotev2.domain.repository.ServerConfigRepository
import dev.leonardo.ocremotev2.domain.repository.ServerConnectionRepository
import dev.leonardo.ocremotev2.domain.repository.ServerRepository
import dev.leonardo.ocremotev2.domain.repository.SessionRepository
import dev.leonardo.ocremotev2.domain.repository.SettingsRepository
import dev.leonardo.ocremotev2.domain.repository.TerminalRepository
import dev.leonardo.ocremotev2.domain.repository.VcsRepository
import dev.leonardo.ocremotev2.fakes.FakeAgentRepository
import dev.leonardo.ocremotev2.fakes.FakeChatRepository
import dev.leonardo.ocremotev2.fakes.FakeDraftRepository
import dev.leonardo.ocremotev2.fakes.FakeFileRepository
import dev.leonardo.ocremotev2.fakes.FakeMcpRepository
import dev.leonardo.ocremotev2.fakes.FakeServerRepository
import dev.leonardo.ocremotev2.fakes.FakeSessionRepository
import dev.leonardo.ocremotev2.fakes.FakeSettingsRepository
import dev.leonardo.ocremotev2.fakes.FakeTerminalRepository
import dev.leonardo.ocremotev2.fakes.FakeVcsRepository
import javax.inject.Singleton

/**
 * Replaces BOTH DomainModule and DataModule with fake repository bindings.
 *
 * DataModule (data/di/) binds ChatRepository + SessionRepository.
 * DomainModule (di/) binds all other repository interfaces.
 *
 * ServerRepositoryImpl implements 5 interfaces; FakeServerRepository does the same,
 * so we bind the single fake instance as all 5 types.
 */
@TestInstallIn(components = [SingletonComponent::class], replaces = [DomainModule::class, DataModule::class])
@Module
@Suppress("unused")
abstract class FakeDomainModule {

    // DataModule replacements
    @Binds @Singleton abstract fun bindChatRepository(impl: FakeChatRepository): ChatRepository
    @Binds @Singleton abstract fun bindSessionRepository(impl: FakeSessionRepository): SessionRepository

    // DomainModule replacements
    @Binds @Singleton abstract fun bindSettingsRepository(impl: FakeSettingsRepository): SettingsRepository
    @Binds @Singleton abstract fun bindAgentRepository(impl: FakeAgentRepository): AgentRepository
    @Binds @Singleton abstract fun bindDraftRepository(impl: FakeDraftRepository): DraftRepository
    @Binds @Singleton abstract fun bindFileRepository(impl: FakeFileRepository): FileRepository
    @Binds @Singleton abstract fun bindVcsRepository(impl: FakeVcsRepository): VcsRepository
    @Binds @Singleton abstract fun bindTerminalRepository(impl: FakeTerminalRepository): TerminalRepository
    @Binds @Singleton abstract fun bindMcpRepository(impl: FakeMcpRepository): McpRepository

    // ServerRepository and its 4 sub-interfaces — all backed by single FakeServerRepository
    @Binds @Singleton abstract fun bindServerRepository(impl: FakeServerRepository): ServerRepository
    @Binds @Singleton abstract fun bindServerConfigRepository(impl: FakeServerRepository): ServerConfigRepository
    @Binds @Singleton abstract fun bindServerConnectionRepository(impl: FakeServerRepository): ServerConnectionRepository
    @Binds @Singleton abstract fun bindLocalServerRepository(impl: FakeServerRepository): LocalServerRepository
    @Binds @Singleton abstract fun bindProviderRepository(impl: FakeServerRepository): ProviderRepository
}
