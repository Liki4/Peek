package io.github.liki4.peek

import android.app.Application

class PeekApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: PeekApp
            private set
    }
}
