/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:OptIn(ExperimentalMaterial3Api::class)

package com.android.systemui.scene.ui.composable

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.RichTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider
import com.android.compose.theme.LocalAndroidColorScheme
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.scene.ui.viewmodel.DualShadeEducationalTooltipsViewModel

@Composable
fun DualShadeEducationalTooltips(
    viewModelFactory: DualShadeEducationalTooltipsViewModel.Factory,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val viewModel =
        rememberViewModel(traceName = "DualShadeEducationalTooltips") {
            viewModelFactory.create(context)
        }

    val visibleTooltip = viewModel.visibleTooltip

    Layout(
        content = {
            AnchoredTooltip(
                isVisible = visibleTooltip != null,
                text = visibleTooltip?.text,
                onShown = visibleTooltip?.onShown,
                onDismissed = visibleTooltip?.onDismissed,
            )
        },
        modifier = modifier.fillMaxSize(),
    ) { measurables, constraints ->
        check(measurables.size == 1)
        val placeable =
            measurables[0].measure(
                Constraints.fixed(
                    width = visibleTooltip?.anchorBounds?.width ?: 0,
                    height = visibleTooltip?.anchorBounds?.height ?: 0,
                )
            )
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeable.place(
                x = visibleTooltip?.anchorBounds?.left ?: 0,
                y = visibleTooltip?.anchorBounds?.top ?: 0,
            )
        }
    }
}

@Composable
private fun AnchoredTooltip(
    isVisible: Boolean,
    text: String?,
    onShown: (() -> Unit)?,
    onDismissed: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val tooltipState = rememberTooltipState(initialIsVisible = false, isPersistent = true)

    LaunchedEffect(isVisible) {
        if (isVisible) {
            onShown?.invoke()
            tooltipState.show()
        } else {
            tooltipState.dismiss()
        }
    }

    val density = LocalDensity.current
    val caretSize = TooltipDefaults.caretSize
    val caretSizePx = with(density) { caretSize.toSize() }
    val gapBetweenPopupAndAnchor = with(density) { 2.dp.roundToPx() }
    val screenEdgePadding = with(density) { 8.dp.roundToPx() }
    val displayCutoutTop = WindowInsets.displayCutout.getTop(density)
    TooltipBox(
        positionProvider =
            remember(caretSizePx, gapBetweenPopupAndAnchor, screenEdgePadding, displayCutoutTop) {
                object : PopupPositionProvider {
                    override fun calculatePosition(
                        anchorBounds: IntRect,
                        windowSize: IntSize,
                        layoutDirection: LayoutDirection,
                        popupContentSize: IntSize,
                    ): IntOffset {
                        // The anchor and popup coordinates are already window-relative.
                        // Do not subtract the cutout inset: that moves the education bubble
                        // upward behind camera cutouts on narrow devices.
                        val preferredX =
                            if (anchorBounds.left + popupContentSize.width < windowSize.width) {
                                anchorBounds.left
                            } else {
                                anchorBounds.right - popupContentSize.width
                            }
                        val maxX =
                            (windowSize.width - popupContentSize.width - screenEdgePadding)
                                .coerceAtLeast(screenEdgePadding)
                        val safeTop = displayCutoutTop + screenEdgePadding
                        val maxY =
                            (windowSize.height - popupContentSize.height - screenEdgePadding)
                                .coerceAtLeast(safeTop)
                        return IntOffset(
                            x = preferredX.coerceIn(screenEdgePadding, maxX),
                            y = (anchorBounds.bottom + gapBetweenPopupAndAnchor)
                                .coerceIn(safeTop, maxY),
                        )
                    }
                }
            },
        tooltip = {
            RichTooltip(
                colors =
                    TooltipDefaults.richTooltipColors(
                        containerColor = LocalAndroidColorScheme.current.tertiaryFixed,
                        contentColor = LocalAndroidColorScheme.current.onTertiaryFixed,
                    ),
                caretShape = TooltipDefaults.caretShape(),
                shadowElevation = 2.dp,
            ) {
                Text(text = text ?: "", modifier = Modifier.padding(8.dp))
            }
        },
        state = tooltipState,
        onDismissRequest = onDismissed,
        modifier = modifier,
    ) {
        Spacer(modifier = Modifier.fillMaxSize())
    }
}
