package com.ali.menbaradkshk

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.ali.menbaradkshk.data.LocalStore
import com.ali.menbaradkshk.notification.NotificationChannels
import com.ali.menbaradkshk.notification.BackgroundScheduler
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessaging
import androidx.work.Configuration

class MinbarApplication : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            // Keep WorkManager's IDs separate from DownloadScheduler.JOB_ID (4210).
            .setJobSchedulerJobIdRange(1_000, 3_000)
            .build()

    override fun onCreate() {
        super.onCreate()
        LocalStore.get(this)
        createNotificationChannels()
        initializeFirebase()
        BackgroundScheduler.scheduleAll(this)
        // استئناف طابور التحميل إن بقيت فيه دروس من جلسة سابقة.
        if (LocalStore.get(this).downloadQueue().isNotEmpty()) {
            com.ali.menbaradkshk.data.DownloadScheduler.enqueue(this)
        }
    }

    private fun initializeFirebase() {
        runCatching {
            val app = FirebaseApp.getApps(this).firstOrNull() ?: FirebaseApp.initializeApp(
                this,
                FirebaseOptions.Builder()
                    .setApiKey("AIzaSyCWAHqbzhfQ-ZcjSSVCAhFFqCTgQ66SdCs")
                    .setApplicationId("1:502388954405:android:6ca4f526675c8c3a89b6cc")
                    .setGcmSenderId("502388954405")
                    .setProjectId("mxqp-8d1e8")
                    .setStorageBucket("mxqp-8d1e8.firebasestorage.app")
                    .build(),
            )
            FirebaseAppCheck.getInstance(app)
                .installAppCheckProviderFactory(MinbarAppCheckProvider.factory())
            if (FirebaseAuth.getInstance(app).currentUser == null) {
                FirebaseAuth.getInstance(app).signInAnonymously()
            }
            if (LocalStore.get(this).notificationsEnabled()) {
                FirebaseMessaging.getInstance().subscribeToTopic("content")
            }
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(
                    NotificationChannels.CONTENT,
                    getString(R.string.content_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
                NotificationChannel(
                    NotificationChannels.WARD,
                    getString(R.string.ward_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
                NotificationChannel(
                    NotificationChannels.DOWNLOADS,
                    "تحميل الدروس",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            ),
        )
    }
}
