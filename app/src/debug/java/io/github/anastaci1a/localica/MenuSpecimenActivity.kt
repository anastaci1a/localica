package io.github.anastaci1a.localica

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.anastaci1a.localica.ui.LocalicaTheme
import io.github.anastaci1a.localica.ui.MenuSpecimenScreen

/** #757: hosts the UI specimen in debug builds. No shipped page links to it. */
class MenuSpecimenActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { LocalicaTheme { MenuSpecimenScreen(onClose = ::finish) } }
    }
}
