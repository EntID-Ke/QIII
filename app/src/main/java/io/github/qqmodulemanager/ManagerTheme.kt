package io.github.qqmodulemanager

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable internal fun ManagerTheme(content:@Composable ()->Unit) {
    MaterialTheme(colorScheme=if(isSystemInDarkTheme()) darkColorScheme(primary=Color(0xFFA8C7FF),primaryContainer=Color(0xFF1C3152),onPrimaryContainer=Color(0xFFD9E6FF)) else lightColorScheme(primary=Color(0xFF2563EB),primaryContainer=Color(0xFFEBF1FF),onPrimaryContainer=Color(0xFF153A78),background=Color(0xFFF7F8FC),surface=Color.White,surfaceContainerHighest=Color(0xFFF0F3F9)),content=content)
}
