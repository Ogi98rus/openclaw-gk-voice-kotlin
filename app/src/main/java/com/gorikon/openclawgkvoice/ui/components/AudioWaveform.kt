package com.gorikon.openclawgkvoice.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.gorikon.openclawgkvoice.ui.theme.WaveformBgColor
import com.gorikon.openclawgkvoice.ui.theme.WaveformColor

/**
 * Визуализация аудио-waveform.
 *
 * Рисует столбцы с высотой, пропорциональной амплитуде.
 * Используется на экране VoiceScreen для визуальной обратной связи.
 *
 * @param amplitudes Массив амплитуд (0.0..1.0), каждая — один столбец
 * @param barWidth Ширина одного столбца
 * @param barGap Расстояние между столбцами
 * @param color Цвет столбцов (по умолчанию — бирюзовый)
 * @param backgroundColor Цвет фона waveform
 */
@Composable
fun AudioWaveform(
    amplitudes: List<Float>,
    modifier: Modifier = Modifier,
    barWidth: Float = 4f,
    barGap: Float = 3f,
    color: Color = WaveformColor,
    backgroundColor: Color = WaveformBgColor
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(80.dp)
    ) {
        // Фон
        drawRect(
            color = backgroundColor,
            size = Size(size.width, size.height)
        )

        if (amplitudes.isEmpty()) return@Canvas

        val barStep = barWidth + barGap
        val maxBars = (size.width / barStep).toInt()
        val displayBars = amplitudes.takeLast(maxBars)
        val centerX = size.width / 2f
        val centerY = size.height / 2f

        // Рисуем столбцы от центра
        displayBars.forEachIndexed { index, amplitude ->
            val barHeight = (amplitude * centerY).coerceAtLeast(2f)

            // Позиция столбца — от центра к краям
            val offsetFromCenter = (index - displayBars.size / 2f) * barStep
            val left = centerX + offsetFromCenter - barWidth / 2f

            // Градиент — ярче в центре, тусклее к краям
            val alpha = 0.3f + 0.7f * (1f - kotlin.math.abs(offsetFromCenter) / centerX).coerceAtLeast(0f)
            val barColor = color.copy(alpha = alpha)

            // Верхняя половина
            drawRect(
                color = barColor,
                topLeft = Offset(left, centerY - barHeight),
                size = Size(barWidth, barHeight)
            )
            // Нижняя половина (зеркально)
            drawRect(
                color = barColor,
                topLeft = Offset(left, centerY),
                size = Size(barWidth, barHeight)
            )
        }
    }
}

/**
 * Простая waveform-визуализация с одной текущей амплитудой.
 * Рисует один столбец, обновляющийся в реальном времени.
 */
@Composable
fun SimpleAmplitudeBar(
    amplitude: Float,
    modifier: Modifier = Modifier,
    color: Color = WaveformColor
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(120.dp)
    ) {
        val barWidth = 12f
        val centerX = size.width / 2f
        val centerY = size.height / 2f
        val barHeight = (amplitude * centerY * 1.5f).coerceIn(4f, centerY)

        // Градиент от центра к краям столбца
        val gradient = Brush.verticalGradient(
            colors = listOf(
                color.copy(alpha = 0.4f),
                color,
                color.copy(alpha = 0.4f)
            ),
            startY = centerY - barHeight,
            endY = centerY + barHeight
        )

        drawRoundRect(
            brush = gradient,
            topLeft = Offset(centerX - barWidth / 2f, centerY - barHeight),
            size = Size(barWidth, barHeight * 2),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f)
        )
    }
}
