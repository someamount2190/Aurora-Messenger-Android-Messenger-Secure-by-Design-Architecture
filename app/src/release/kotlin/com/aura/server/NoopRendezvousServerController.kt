package com.aura.server

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Release build of [RendezvousServerController]: an inert no-op. The in-app rendezvous
 * server is a developer tool and is not compiled into release APKs (user rule: no dev
 * code in release), so there is nothing to start — status stays [Status.Stopped] and
 * every call is a no-op. Hilt binds this via the release [com.aura.di.ServerControllerModule].
 */
@Singleton
class NoopRendezvousServerController @Inject constructor() : RendezvousServerController {
    private val _status = MutableStateFlow<RendezvousServerController.Status>(
        RendezvousServerController.Status.Stopped
    )
    override val status: StateFlow<RendezvousServerController.Status> = _status

    override fun start(port: Int) { /* no in-app server in release */ }
    override fun stop() { /* no-op */ }
    override fun refreshStatus() { /* no-op */ }
}
