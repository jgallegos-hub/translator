package com.travel2chicago.gemmapipeline

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat

private const val TAG = "ListeningService"
private const val CHANNEL_ID = "listening"
private const val NOTIFICATION_ID = 1

/**
 * Keeps the translator hearing in field use (2026-11-11): with the screen off or another app in full screen,
 * Android silences the microphone of background apps unless they run a foreground service of type
 * microphone. The capture itself stays in the ViewModel; this service only keeps the process "in use",
 * holds a partial wake lock, and shows the "Traductor escuchando" notification with a Stop action.
 * Started when the pipeline starts (the app is in the foreground then, as Android requires).
 */
class ListeningService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var focusRequest: AudioFocusRequest? = null

    /** Tells the system we are an app that plays audio (E013), the same way a music player does. */
    private fun requestAudioFocus() {
        if (focusRequest != null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener { Log.i(TAG, "audio focus change: $it") }
            .build()
        val r = (getSystemService(Context.AUDIO_SERVICE) as AudioManager).requestAudioFocus(req)
        focusRequest = req
        Log.i(TAG, "audio focus request -> $r (1 = granted)")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_STOP_PIPELINE, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Traductor escuchando")
            .setContentText("Toca para abrir")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Detener", stop).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Microphone AND media playback (E013, 2026-11-12): declared as microphone only, the mic and the server
            // kept working with the screen locked, but Eric's audio stayed queued until the phone was unlocked.
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        if (wakeLock == null) {
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gemmapipeline:listening")
                .apply { setReferenceCounted(false); acquire(MAX_WAKE_MS) }
        }
        requestAudioFocus()
        Log.i(TAG, "foreground service ON (microphone + media playback)")
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        focusRequest?.let { (getSystemService(Context.AUDIO_SERVICE) as AudioManager).abandonAudioFocusRequest(it) }
        focusRequest = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        Log.i(TAG, "foreground service OFF")
        super.onDestroy()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Traductor escuchando", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    companion object {
        /** A work shift, as a safety cap: the wake lock is released on Stop anyway. */
        private const val MAX_WAKE_MS = 12L * 60 * 60 * 1000

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, ListeningService::class.java)) }
                .onFailure { Log.w(TAG, "could not start: ${it.message}") }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ListeningService::class.java))
        }
    }
}
