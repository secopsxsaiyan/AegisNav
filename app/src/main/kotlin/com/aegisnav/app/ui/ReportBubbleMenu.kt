package com.aegisnav.app.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.ui.window.Dialog
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Redesigned report bubble menu with sub-option second-level sheet.
 * 8 categories; sub-option sheet shown before submitting when applicable.
 * Police category also has an "is group (3+)" toggle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportBubbleMenu(
    modifier: Modifier = Modifier,
    onReportSelected: (category: ReportCategory, subOption: String?, isGroup: Boolean) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var pendingCategory by remember { mutableStateOf<ReportCategory?>(null) }

    // Sub-option sheet
    pendingCategory?.let { category ->
        if (category.subtypes.isEmpty()) {
            // No sub-options: submit immediately
            LaunchedEffect(category) {
                onReportSelected(category, null, false)
                pendingCategory = null
                expanded = false
            }
        } else {
            SubOptionSheet(
                category = category,
                onSubOptionSelected = { subOption, isGroup ->
                    onReportSelected(category, subOption, isGroup)
                    pendingCategory = null
                    expanded = false
                },
                onDismiss = { pendingCategory = null }
            )
        }
    }

    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
    ) {
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 })
        ) {
            CategoryBubbleGrid(
                categories = ReportCategory.entries,
                onCategorySelected = { category ->
                    if (category.subtypes.isEmpty()) {
                        onReportSelected(category, null, false)
                        expanded = false
                    } else {
                        pendingCategory = category
                    }
                }
            )
        }
        FloatingActionButton(
            onClick = { expanded = !expanded },
            containerColor = MaterialTheme.colorScheme.primary
        ) {
            Icon(
                imageVector = if (expanded) Icons.Default.Close else Icons.Default.Add,
                contentDescription = if (expanded) "Close" else "Report"
            )
        }
    }
}

@Composable
private fun SubOptionSheet(
    category: ReportCategory,
    onSubOptionSelected: (subOption: String, isGroup: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var isGroup by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 4.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "${category.emoji} ${category.label}",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            if (category == ReportCategory.POLICE) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Checkbox(
                        checked = isGroup,
                        onCheckedChange = { isGroup = it }
                    )
                    Text("Group (3+ cops)", style = MaterialTheme.typography.bodyMedium)
                }
                HorizontalDivider()
            }

            Text("Select side:", style = MaterialTheme.typography.labelMedium)

            category.subtypes.forEach { subOption ->
                Button(
                    onClick = { onSubOptionSelected(subOption, isGroup) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = categoryColor(category)
                    )
                ) {
                    Text(subOption, fontWeight = FontWeight.Medium)
                }
            }
        }
        } // Surface
    } // Dialog
}

@Composable
private fun CategoryBubbleGrid(
    categories: List<ReportCategory>,
    onCategorySelected: (ReportCategory) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.End
    ) {
        // Row 1: first 4
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            categories.take(4).forEach { category ->
                CategoryBubble(category = category, size = 52.dp, onClick = { onCategorySelected(category) })
            }
        }
        // Row 2: next 4
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            categories.drop(4).forEach { category ->
                CategoryBubble(category = category, size = 52.dp, onClick = { onCategorySelected(category) })
            }
        }
    }
}

@Composable
fun CategoryBubble(
    category: ReportCategory,
    size: Dp = 52.dp,
    onClick: () -> Unit,
    onLongPress: () -> Unit = {}
) {
    val backgroundColor = categoryColor(category)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.pointerInput(Unit) {
            detectTapGestures(
                onTap = { onClick() },
                onLongPress = { onLongPress() }
            )
        }
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(backgroundColor),
            contentAlignment = Alignment.Center
        ) {
            Text(text = category.emoji, fontSize = 22.sp)
        }
        Text(
            text = category.label,
            fontSize = 9.sp,
            color = Color.White,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .background(Color(0x99000000), CircleShape)
                .padding(horizontal = 4.dp, vertical = 1.dp)
                .width(60.dp)
        )
    }
}

private fun categoryColor(category: ReportCategory): Color = when (category) {
    ReportCategory.POLICE       -> Color(0xFF1565C0)
    ReportCategory.ALPR         -> Color(0xFFE65100)
    ReportCategory.SURVEILLANCE -> Color(0xFF37474F)
    ReportCategory.ACCIDENT     -> Color(0xFFD84315)
    ReportCategory.HAZARD       -> Color(0xFFF57F17)
    ReportCategory.WEATHER      -> Color(0xFF0277BD)
    ReportCategory.CONSTRUCTION -> Color(0xFF4E342E)
    ReportCategory.ROAD_CLOSURE -> Color(0xFFC62828)
}
