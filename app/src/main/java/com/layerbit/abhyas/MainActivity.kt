package com.layerbit.abhyas

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.layerbit.abhyas.ui.AbhyasApp
import com.layerbit.abhyas.ui.theme.AbhyasTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            AbhyasTheme {
                AbhyasApp()
            }
        }
    }
}
