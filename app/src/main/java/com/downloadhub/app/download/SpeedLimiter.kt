package com.downloadhub.app.download

/**
 * The app-wide speed cap is the shared one from :core. This file used to hold a second
 * copy of the same token bucket; the HTTP engine is now the shared engine, which takes
 * the shared type.
 */
typealias SpeedLimiter = com.downloadhub.core.SpeedLimiter
