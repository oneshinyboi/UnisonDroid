package io.unisondroid.app.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

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
    NavHost(navController = navController, startDestination = Routes.PROFILES) {
        composable(Routes.PROFILES) {
            ProfilesScreen(
                onOpenProfile = { id -> navController.navigate(Routes.editor(id)) },
                onStartSync = { id -> navController.navigate(Routes.run(id)) },
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
            val raw = entry.arguments?.getString("id")
            ProfileEditorScreen(
                profileId = raw?.takeIf { it.isNotEmpty() && it != "new" },
                onSaved = { navController.popBackStack() },
            )
        }
        composable(
            route = Routes.RUN,
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { entry ->
            RunScreen(profileId = entry.arguments?.getString("id").orEmpty())
        }
        composable(Routes.KEYS) { KeysScreen() }
        composable(Routes.ABOUT) { AboutScreen() }
    }
}
