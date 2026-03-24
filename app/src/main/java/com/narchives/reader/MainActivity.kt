package com.narchives.reader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.narchives.reader.ui.theme.NarchivesTheme
import com.narchives.reader.ui.navigation.NarchivesNavGraph

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NarchivesTheme {
                NarchivesNavGraph()
            }
        }
    }
}
