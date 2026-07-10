package com.ripple.town

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.ripple.town.core.ui.RippleTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val mainViewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RippleTheme {
                RippleApp(mainViewModel)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        mainViewModel.setForeground(true)
    }

    override fun onStop() {
        // Simulation never runs while the app is closed; a checkpoint is saved
        // on the way out and time is caught up (bounded) on the way back in.
        mainViewModel.setForeground(false)
        super.onStop()
    }
}
