package com.trackstudio.app.data.model

/**
 * Deliberately small for Phase 1 - just enough for the importer to tag
 * footage correctly. Log/ACES profile handling belongs to a future
 * color-management module, not the import screen.
 */
enum class ColorSpace(val label: String) {
    SRGB("sRGB"),
    REC709("Rec. 709"),
    LOG("Log (generic)"),
    LINEAR("Linear")
}
