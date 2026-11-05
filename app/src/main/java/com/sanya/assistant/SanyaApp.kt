package com.sanya.assistant

import android.app.Activity
import android.app.Application
import android.os.Bundle

class SanyaApp : Application() {

    override fun onCreate() {
        super.onCreate()

        ErrorLog.install(this)

        registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {

            override fun onActivityStarted(a: Activity) {
                try {
                    Ui.skin(a)
                } catch (e: Throwable) {
                    ErrorLog.add(a, "Theme", e)
                }
            }

            override fun onActivityCreated(a: Activity, s: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, s: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }
}
