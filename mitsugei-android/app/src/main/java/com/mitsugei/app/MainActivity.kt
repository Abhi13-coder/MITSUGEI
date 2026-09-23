package com.mitsugei.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.mitsugei.app.ui.auth.AccountScreen
import com.mitsugei.app.ui.search.SearchScreen
import com.mitsugei.app.ui.settings.SettingsScreen
import com.mitsugei.app.ui.theme.AppThemeMode
import com.mitsugei.app.ui.theme.MitsugeiTheme
import com.mitsugei.app.ui.tools.ToolsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            var themeMode by remember { mutableStateOf(AppThemeMode.SYSTEM) }
            MitsugeiTheme(themeMode = themeMode) {
                Surface(Modifier.fillMaxSize()) {
                    val nav = rememberNavController()
                    val dark = when (themeMode) {
                        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
                        AppThemeMode.LIGHT -> false
                        AppThemeMode.DARK -> true
                    }
                    NavHost(navController = nav, startDestination = "search") {
                        composable("search") {
                            SearchScreen(
                                onOpenTools = { nav.navigate("tools") },
                                onOpenSettings = { nav.navigate("settings") },
                                onOpenAccount = { nav.navigate("account") },
                                darkTheme = dark
                            )
                        }
                        composable("tools") {
                            ToolsScreen(onBack = { nav.popBackStack() })
                        }
                        composable("settings") {
                            SettingsScreen(
                                themeMode = themeMode,
                                onThemeChange = { themeMode = it },
                                onBack = { nav.popBackStack() }
                            )
                        }
                        composable("account") {
                            AccountScreen(onBack = { nav.popBackStack() })
                        }
                    }
                }
            }
        }
    }
}
