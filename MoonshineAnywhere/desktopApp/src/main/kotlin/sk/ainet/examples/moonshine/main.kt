package sk.ainet.examples.moonshine

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import sk.ainet.examples.moonshine.ui.App

fun main() = application {
    val platform = DesktopSpeechPlatform()
    Window(onCloseRequest = ::exitApplication, title = "Moonshine Anywhere", state = rememberWindowState(width = 760.dp, height = 720.dp)) {
        App(platform)
    }
}
