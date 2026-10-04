package org.resqmesh.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/** User-started relay session. Never opens the microphone, camera, or location in background. */
class RelayService : Service() {
    private val mesh get() = (application as MeshApplication).mesh
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            mesh.setBackgroundRelayEnabled(false) {}; stopSelf(); return START_NOT_STICKY
        }
        if (!mesh.backgroundRelayEnabled()) { stopSelf(); return START_NOT_STICKY }
        try {
            val simulated = getSharedPreferences("resqmesh", MODE_PRIVATE).getBoolean("simulation", true)
            val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                if (simulated) 0 else ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification(simulated), types)
            else startForeground(NOTIFICATION, notification(simulated))
            mesh.relayServiceStarted()
        } catch (_: RuntimeException) {
            mesh.relayServiceStopped("Android could not start background relay. Open Settings to check notifications and Nearby permissions.")
            stopSelf(); return START_NOT_STICKY
        }
        // The OS can recover a user-started session. This does not bypass force-stop or OEM restrictions.
        return START_STICKY
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        mesh.relayServiceStopped("Android ended the background transfer window. Open ResQMesh to resume.")
        showResumeNotification(this, "Android paused background transfers. Open ResQMesh to resume.")
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    override fun onDestroy() { mesh.relayServiceStopped(); super.onDestroy() }
    private fun notification(simulated: Boolean): Notification {
        createChannel(this)
        val stop = PendingIntent.getService(this, 42, Intent(this, RelayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_mesh)
            .setContentTitle(if (simulated) "ResQMesh simulation is running" else "ResQMesh relay is running")
            .setContentText("Saved SOS and receipts retry in the background. Tap to view status.")
            .setStyle(Notification.BigTextStyle().bigText("Saved SOS and receipts retry in the background. New nearby connections require confirmation in the app. Delivery and human acknowledgement remain separate."))
            .setContentIntent(openIntent(this)).setOngoing(true).setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, "Stop background relay", stop).build()).build()
    }
    companion object {
        const val ACTION_STOP = "org.resqmesh.app.STOP_BACKGROUND_RELAY"
        private const val CHANNEL = "relay-session"
        private const val NOTIFICATION = 701
        private fun openIntent(context: Context) = PendingIntent.getActivity(context, 41,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        private fun createChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Background relay", NotificationManager.IMPORTANCE_LOW))
        }
        fun showResumeNotification(context: Context, text: String) {
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            createChannel(context)
            context.getSystemService(NotificationManager::class.java).notify(702,
                Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_mesh).setContentTitle("Resume ResQMesh relay")
                    .setContentText(text).setContentIntent(openIntent(context)).setAutoCancel(true)
                    .setVisibility(Notification.VISIBILITY_PRIVATE).build())
        }
    }
}

/** Boot may not start dataSync on Android 15+. A user tap safely resumes the opted-in session. */
class RelayRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        if (context.getSharedPreferences("resqmesh", Context.MODE_PRIVATE).getBoolean("backgroundRelayEnabled", false))
            RelayService.showResumeNotification(context, "Your saved reports are safe. Open the app to resume background relay.")
    }
}
