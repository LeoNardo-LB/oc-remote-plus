package dev.minios.ocremote.domain.repository

/**
 * Aggregate repository interface for server management.
 * Split into 4 sub-interfaces following ISP:
 * - [ServerConfigRepository]: Server CRUD
 * - [ServerConnectionRepository]: Connection lifecycle
 * - [LocalServerRepository]: Local server management
 * - [ProviderRepository]: Provider/model management
 */
interface ServerRepository :
    ServerConfigRepository,
    ServerConnectionRepository,
    LocalServerRepository,
    ProviderRepository
