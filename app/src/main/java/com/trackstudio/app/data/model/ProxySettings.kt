package com.trackstudio.app.data.model

enum class ProxyResolution(val label: String, val scale: Float) {
    FULL("Full", 1.0f),
    HALF("1/2", 0.5f),
    QUARTER("1/4", 0.25f),
    EIGHTH("1/8", 0.125f)
}

data class ProxySettings(
    val enabled: Boolean = true,
    val resolution: ProxyResolution = ProxyResolution.HALF,
    val jpegQuality: Int = 85,
    // Proxies live under the project's own cache dir, never on shared
    // storage - they're disposable, regenerable, and shouldn't show up in
    // the user's gallery app.
    val cacheSubdir: String = "proxy_cache"
) {
    fun estimatedFrameDimensions(sourceWidth: Int, sourceHeight: Int): Pair<Int, Int> {
        val w = (sourceWidth * resolution.scale).toInt().coerceAtLeast(1)
        val h = (sourceHeight * resolution.scale).toInt().coerceAtLeast(1)
        return w to h
    }
}
