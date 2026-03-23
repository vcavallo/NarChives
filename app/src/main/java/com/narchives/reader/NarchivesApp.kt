package com.narchives.reader

import android.app.Application
import com.narchives.reader.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NarchivesApp : Application() {

    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        // Seed default relays on first launch and connect
        appScope.launch {
            container.relayRepository.seedDefaultRelaysIfNeeded()
            container.nostrClient.connect()
        }
    }
}
