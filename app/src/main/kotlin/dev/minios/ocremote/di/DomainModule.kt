package dev.minios.ocremote.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.minios.ocremote.data.repository.DraftDataStore
import dev.minios.ocremote.domain.repository.DraftRepository

@Module
@InstallIn(SingletonComponent::class)
abstract class DomainModule {

    @Binds
    abstract fun bindDraftRepository(impl: DraftDataStore): DraftRepository
}
