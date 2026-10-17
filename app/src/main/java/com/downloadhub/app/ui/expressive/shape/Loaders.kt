package com.downloadhub.app.ui.expressive.shape

import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The library's loading indicator, for waits of unknown length. Confined here beside
 * `StateShape` because it is still experimental in material3 1.5.0-beta01. Screens call
 * these two and never the library directly.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun Loader(modifier: Modifier = Modifier) = LoadingIndicator(modifier = modifier)

/** The same on a tonal disc, for use over content or in an empty area. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ContainedLoader(modifier: Modifier = Modifier) = ContainedLoadingIndicator(modifier = modifier)
