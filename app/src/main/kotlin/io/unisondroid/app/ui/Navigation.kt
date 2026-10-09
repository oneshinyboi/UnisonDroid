package io.unisondroid.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.unisondroid.app.service.SyncService

object Routes {
    const val PROFILES = "profiles"
    const val EDITOR = "editor/{id}"
    const val RUN = "run/{id}"
    const val KEYS = "keys"
    const val ABOUT = "about"

    const val EDITOR_NEW = "editor/new"

    fun editor(id: String?): String = if (id.isNullOrEmpty()) EDITOR_NEW else "editor/$id"

    fun run(id: String): String = "run/$id"
}

@Composable
fun UnisonDroidNavHost(navController: NavHostController = rememberNavController()) {
    val context = LocalContext.current

    NavHost(navController = navController, startDestination = Routes.PROFILES) {
        composable(Routes.PROFILES) {
            ProfilesScreen(
                onOpenProfile = { id -> navController.navigate(Routes.editor(id)) },
                onStartSync = { id ->
                    context.startService(SyncService.intent(context, id))
                    navController.navigate(Routes.run(id))
                },
            )
        }
        composable(
            route = Routes.EDITOR,
            arguments = listOf(
                navArgument("id") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            PlaceholderScreen(
                title = "Profile editor",
                subtitle = entry.arguments?.getString("id"),
            )
        }
        composable(
            route = Routes.RUN,
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { entry ->
            PlaceholderScreen(
                title = "Sync",
                subtitle = entry.arguments?.getString("id"),
            )
        }
        composable(Routes.KEYS) { PlaceholderScreen(title = "SSH keys") }
        composable(Routes.ABOUT) { PlaceholderScreen(title = "About") }
    }
}

@Composable
private fun PlaceholderScreen(title: String, subtitle: String? = null) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = if (subtitle == null) title else "$title: $subtitle",
            style = MaterialTheme.typography.headlineSmall,
        )
    }
}
