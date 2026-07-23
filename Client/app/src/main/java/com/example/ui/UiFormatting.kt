package com.example.ui

/**
 * Compatibility entry point for callers that used the original package before UI extraction.
 */
fun formatTime(milliseconds: Long): String =
    com.example.ui.components.formatTime(milliseconds)
