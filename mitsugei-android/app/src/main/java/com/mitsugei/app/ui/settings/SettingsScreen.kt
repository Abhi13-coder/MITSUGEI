package com.mitsugei.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mitsugei.app.ui.theme.AppThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    themeMode: AppThemeMode,
    onThemeChange: (AppThemeMode) -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            item {
                Text(
                    "Theme",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }
            item {
                ThemeRow("System default", AppThemeMode.SYSTEM, themeMode, onThemeChange)
                ThemeRow("Light", AppThemeMode.LIGHT, themeMode, onThemeChange)
                ThemeRow("Dark", AppThemeMode.DARK, themeMode, onThemeChange)
            }
            item {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(
                    "About",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
                ListItem(
                    headlineContent = { Text("Mitsugei") },
                    supportingContent = { Text("Open search · Android 8–15+ · v1.0.0") }
                )
            }
        }
    }
}

@Composable
private fun ThemeRow(
    label: String,
    mode: AppThemeMode,
    current: AppThemeMode,
    onChange: (AppThemeMode) -> Unit
) {
    ListItem(
        headlineContent = { Text(label) },
        trailingContent = {
            if (mode == current) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
        },
        modifier = Modifier.clickable { onChange(mode) }
    )
}
