package com.aura.di

import com.aura.server.DebugRendezvousServerController
import com.aura.server.RendezvousServerController
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Debug variant: binds the real in-app rendezvous server controller. A release-variant
 * twin of this module (same name, `src/release`) binds the no-op instead, so the server
 * implementation is never compiled into a release APK.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ServerControllerModule {
    @Binds
    @Singleton
    abstract fun bindServerController(
        impl: DebugRendezvousServerController
    ): RendezvousServerController
}
