package com.lingocast.app.ui.theme

import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

/**
 * Material 3 Expressive physics-based motion tokens.
 * Spatial springs govern physical movement and layout transformations;
 * Effect springs govern color, alpha, and elevation emphasis transitions.
 */
object LingoMotion {
    fun <T> spatialDefault() = spring<T>(
        dampingRatio = 0.78f,
        stiffness = 380f
    )

    fun <T> spatialFast() = spring<T>(
        dampingRatio = 0.82f,
        stiffness = 560f
    )

    fun <T> spatialSlow() = spring<T>(
        dampingRatio = 0.85f,
        stiffness = 240f
    )

    fun <T> expressiveBouncy() = spring<T>(
        dampingRatio = 0.68f,
        stiffness = 420f
    )

    fun <T> effectsDefault() = spring<T>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = 520f
    )

    val spatialOffsetDefault = spring<IntOffset>(
        dampingRatio = 0.78f,
        stiffness = 380f
    )

    val SeriesCoverCornerShape = RoundedCornerShape(22.dp)

    @OptIn(ExperimentalSharedTransitionApi::class)
    val SeriesCoverBoundsTransform = BoundsTransform { _, _ ->
        spatialDefault()
    }
}
