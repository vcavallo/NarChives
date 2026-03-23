package com.narchives.reader

import android.app.Application
import com.narchives.reader.di.AppContainer

class NarchivesApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
