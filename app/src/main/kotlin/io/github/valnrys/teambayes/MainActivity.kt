package io.github.valnrys.teambayes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.valnrys.teambayes.tonight.TonightRoute
import io.github.valnrys.teambayes.ui.theme.TeamBayesTheme

/**
 * The app's only activity. Every screen is a Compose function shown inside it.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Draw behind the status and navigation bars; screens apply the insets they need.
        enableEdgeToEdge()
        setContent {
            TeamBayesTheme {
                TonightRoute()
            }
        }
    }
}
