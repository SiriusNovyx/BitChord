package com.music.bitchord.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

internal object DesktopPerformance {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private const val KEY = "desktop_performance_mode"
    private val _mode = MutableStateFlow(
        runCatching {
            DesktopPerformanceMode.valueOf(
                (System.getProperty("bitchord.performance") ?: DesktopPersistence().string(KEY, "BALANCED")).uppercase(),
            )
        }.getOrDefault(DesktopPerformanceMode.BALANCED),
    )
    val mode: StateFlow<DesktopPerformanceMode> = _mode
    val policy: StateFlow<DesktopVisualPolicy> = combine(_mode, DesktopWindowVisibility.visualActive) { mode, active ->
        DesktopVisualPolicy(mode, visible = active, minimized = false)
    }.stateIn(scope, SharingStarted.Eagerly, DesktopVisualPolicy(_mode.value, true, false))

    val reduceMotion = combine(policy, DesktopAppearanceSettings.reduceAnimation) { visual, preferred ->
        visual.reduceMotion || preferred
    }.stateIn(scope, SharingStarted.Eagerly, policy.value.reduceMotion || DesktopAppearanceSettings.reduceAnimation.value)
    val reduceBlur = combine(policy, DesktopAppearanceSettings.reduceDynamicBlur) { visual, preferred ->
        visual.reduceBlur || preferred
    }.stateIn(scope, SharingStarted.Eagerly, policy.value.reduceBlur || DesktopAppearanceSettings.reduceDynamicBlur.value)

    fun setMode(value: DesktopPerformanceMode) {
        DesktopPersistence().saveString(KEY, value.name)
        _mode.value = value
    }
}
