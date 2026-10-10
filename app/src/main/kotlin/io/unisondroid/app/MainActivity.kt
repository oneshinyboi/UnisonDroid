package io.unisondroid.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import io.unisondroid.app.service.ServiceLocator
import io.unisondroid.app.service.SyncScheduler
import io.unisondroid.app.ui.UnisonDroidNavHost
import io.unisondroid.app.ui.theme.UnisonDroidTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            SyncScheduler(this@MainActivity).reconcile(
                ServiceLocator.profiles(this@MainActivity).profiles(),
                ServiceLocator.settings(this@MainActivity).get(),
            )
        }
        setContent {
            UnisonDroidTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    UnisonDroidNavHost()
                }
            }
        }
    }
}
