package io.github.PctAIGM.procview

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.PctAIGM.procview.ui.ProcViewApp
import io.github.PctAIGM.procview.ui.theme.ProcViewTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ProcViewTheme {
                ProcViewApp()
            }
        }
    }
}
