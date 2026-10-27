package com.aura.di

import com.aura.server.NoopRendezvousServerController
import com.aura.server.RendezvousServerController
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Release variant: binds the inert no-op rendezvous server controller. The real
 * implementation lives only in `src/debug`, so release builds never link the in-app
 * server (user rule: no dev code in release).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ServerControllerModule {
    @Binds
    @Singleton
    abstract fun bindServerController(
        impl: NoopRendezvousServerController
    ): RendezvousServerController
}
