package com.gorikon.openclawgkvoice.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.gorikon.openclawgkvoice.MainActivity
import com.gorikon.openclawgkvoice.R

/**
 * Foreground-сервис для записи голоса в фоне.
 *
 * Обеспечивает непрерывную запись аудио даже когда приложение свёрнуто.
 * Показывает постоянное уведомление «Запись голоса...» для информирования пользователя.
 *
 * Запускается: VoiceViewModel.startRecording() → startForegroundService()
 * Останавливается: VoiceViewModel.stopRecording() → stopForeground() + stopSelf()
 */
class VoiceRecordingService : Service() {

    private val binder = LocalBinder()

    companion object {
        const val CHANNEL_ID = "voice_recording_channel"
        const val NOTIFICATION_ID = 1001

        /**
         * Создать NotificationChannel для Android 8.0+.
         * Вызывается из Application.onCreate() или перед стартом сервиса.
         */
        fun createNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Запись голоса",
                    NotificationManager.IMPORTANCE_LOW // LOW — не показывает звуковое уведомление
                ).apply {
                    description = "Уведомление о активной записи голоса"
                    setShowBadge(false)
                }

                val notificationManager =
                    context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.createNotificationChannel(channel)
            }
        }

        /**
         * Запустить ForegroundService для записи.
         */
        fun start(context: Context) {
            val intent = Intent(context, VoiceRecordingService::class.java).apply {
                action = "START"
            }
            ContextCompat.startForegroundService(context, intent)
        }

        /**
         * Остановить ForegroundService.
         */
        fun stop(context: Context) {
            val intent = Intent(context, VoiceRecordingService::class.java).apply {
                action = "STOP"
            }
            context.startService(intent)
        }

        /**
         * Поставить сервис на паузу (скрыть индикатор записи).
         */
        fun pause(context: Context) {
            val intent = Intent(context, VoiceRecordingService::class.java).apply {
                action = "PAUSE"
            }
            context.startService(intent)
        }
    }

    /**
     * Binder для связи Activity/ViewModel с сервисом.
     */
    inner class LocalBinder : Binder() {
        fun getService(): VoiceRecordingService = this@VoiceRecordingService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Запускаем сервис в foreground режиме с уведомлением
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)

        // START_NOT_STICKLY — не перезапускать если система убила сервис
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        // Сервис остановлен — уведомление убирается автоматически через stopForeground
    }

    /**
     * Построить уведомление для foreground-режима.
     * Содержит кнопку для быстрого возврата в приложение.
     */
    private fun buildNotification(): Notification {
        // PendingIntent для возврата в приложение при клике на уведомление
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Запись голоса...")
            .setContentText("OpenClaw GK Voice записывает аудио")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now) // Стандартная иконка микрофона
            .setContentIntent(pendingIntent)
            .setOngoing(true) // Нельзя свайпнуть
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
