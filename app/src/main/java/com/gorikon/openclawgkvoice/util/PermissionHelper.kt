package com.gorikon.openclawgkvoice.util

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Хелпер для работы с runtime-разрешениями.
 *
 * Запись аудио требует RECORD_AUDIO разрешения, которое запрашивается у пользователя
 * на Android 6.0+ (API 23+) во время выполнения.
 */
object PermissionHelper {

    /**
     * Константа: код запроса разрешения на запись аудио.
     * Используется в onActivityResult / onRequestPermissionsResult.
     */
    const val REQUEST_RECORD_AUDIO_PERMISSION = 1001

    /**
     * Проверить, есть ли разрешение на запись аудио.
     *
     * @return true если разрешение RECORD_AUDIO предоставлено
     */
    fun checkAudioPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Запросить разрешение на запись аудио у пользователя.
     *
     * Вызывается из Activity. Результат придёт в onRequestPermissionsResult.
     *
     * @param activity Activity, из которой запрашивается разрешение
     */
    fun requestAudioPermission(activity: Activity) {
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            REQUEST_RECORD_AUDIO_PERMISSION
        )
    }

    /**
     * Проверить, нужно ли показывать rationale (объяснение) пользователю.
     *
     * Возвращает true, если пользователь ранее отклонил разрешение,
     * но не выбрал «Больше не спрашивать».
     */
    fun shouldShowRationale(activity: Activity): Boolean {
        return ActivityCompat.shouldShowRequestPermissionRationale(
            activity,
            Manifest.permission.RECORD_AUDIO
        )
    }
}
