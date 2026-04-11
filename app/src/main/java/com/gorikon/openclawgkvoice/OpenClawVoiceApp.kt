package com.gorikon.openclawgkvoice

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Hilt Application class — точка инициализации DI-графа.
 * Запускается раньше всех, обеспечивает инъекцию зависимостей во весь проект.
 */
@HiltAndroidApp
class OpenClawVoiceApp : Application()
