package com.trinotify.app

import android.app.Application
import com.trinotify.app.logic.RuleCache
import com.trinotify.app.service.Sounder

class TrinotifyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Sounder.ensureChannel(this)
        RuleCache.start(this)
    }
}
