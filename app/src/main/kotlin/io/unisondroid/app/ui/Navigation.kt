package io.unisondroid.app.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.unisondroid.app.sync.SyncVariant

object Routes {
    const val PROFILES = "profiles"
    const val EDITOR = "editor/{id}"
    const val RUN = "run/{id}?variant={variant}&confirmed={confirmed}"
    const val RESOLVE = "resolve/{id}"
    const val KEYS = "keys"
    const val ABOUT = "about"
    const val SETTINGS = "settings"

    const val EDITOR_NEW = "editor/new"

    fun editor(id: String?): String = if (id.isNullOrEmpty()) EDITOR_NEW else "editor/$id"

    fun run(
        id: String,
        variant: SyncVariant = SyncVariant.TWO_WAY,
        confirmed: Boolean = false,
    ): String = "run/$id?variant=${variant.name}&confirmed=$confirmed"

    fun resolve(id: String): String = "resolve/$id"
}

@Composable
fun UnisonDroidNavHost(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = Routes.PROFILES) {
        composable(Routes.PROFILES) {
            ProfilesScreen(
                onOpenProfile = { id -> navController.navigate(Routes.editor(id)) },
                onStartSync = { id -> navController.navigate(Routes.run(id)) },
                onOpenKeys = { navController.navigate(Routes.KEYS) },
                onOpenAbout = { navController.navigate(Routes.ABOUT) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onResolveConflicts = { id -> navController.navigate(Routes.resolve(id)) },
                onRunVariant = { id, variant, confirmed ->
                    navController.navigate(Routes.run(id, variant, confirmed))
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
            val raw = entry.arguments?.getString("id")
            ProfileEditorScreen(
                profileId = raw?.takeIf { it.isNotEmpty() && it != "new" },
                onSaved = { navController.popBackStack() },
            )
        }
        composable(
            route = Routes.RUN,
            arguments = listOf(
                navArgument("id") { type = NavType.StringType },
                navArgument("variant") {
                    type = NavType.StringType
                    defaultValue = SyncVariant.TWO_WAY.name
                },
                navArgument("confirmed") {
                    type = NavType.StringType
                    defaultValue = "false"
                },
            ),
        ) { entry ->
            val profileId = entry.arguments?.getString("id").orEmpty()
            val variant = entry.arguments?.getString("variant")
                ?.let { runCatching { SyncVariant.valueOf(it) }.getOrNull() }
                ?: SyncVariant.TWO_WAY
            val confirmed = entry.arguments?.getString("confirmed")?.toBoolean() ?: false
            RunScreen(
                profileId = profileId,
                variant = variant,
                confirmed = confirmed,
                onResolveConflicts = { navController.navigate(Routes.resolve(profileId)) },
            )
        }
        composable(
            route = Routes.RESOLVE,
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { entry ->
            ResolveConflictsScreen(
                profileId = entry.arguments?.getString("id").orEmpty(),
                onDone = { navController.popBackStack() },
            )
        }
        composable(Routes.KEYS) { KeysScreen() }
        composable(Routes.ABOUT) { AboutScreen() }
        composable(Routes.SETTINGS) { SettingsScreen() }
    }
}
