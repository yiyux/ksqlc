package ksqlc.app.ui

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.sp

/** Inter (SIL OFL 1.1, see resources/fonts/OFL-Inter.txt) for all interface text. */
val InterFamily: FontFamily = FontFamily(
    Font(resource = "fonts/Inter-Regular.ttf", weight = FontWeight.Normal, style = FontStyle.Normal),
    Font(resource = "fonts/Inter-SemiBold.ttf", weight = FontWeight.SemiBold, style = FontStyle.Normal),
    Font(resource = "fonts/Inter-Bold.ttf", weight = FontWeight.Bold, style = FontStyle.Normal),
)

/** Interface text at the size fields and labels use. */
val UiStyle = TextStyle(fontFamily = InterFamily, fontSize = 13.sp)

/** JetBrains Mono (SIL OFL 1.1, see resources/fonts/OFL.txt) for the editor, grid and output. */
val MonoFamily: FontFamily = FontFamily(
    Font(resource = "fonts/JetBrainsMono-Regular.ttf", weight = FontWeight.Normal, style = FontStyle.Normal),
    Font(resource = "fonts/JetBrainsMono-Bold.ttf", weight = FontWeight.Bold, style = FontStyle.Normal),
    Font(resource = "fonts/JetBrainsMono-Italic.ttf", weight = FontWeight.Normal, style = FontStyle.Italic),
)

/** Editor/grid text. */
val MonoStyle = TextStyle(fontFamily = MonoFamily, fontSize = 13.sp, lineHeight = 18.sp, fontFeatureSettings = "liga 0, calt 0")  // ligatures off, as in the reference

/** Dense UI text, roughly the size of a desktop IDE rather than Material's phone defaults. */
fun compactTypography(): Typography {
    val base = Typography()
    fun s(size: Int, weight: FontWeight = FontWeight.Normal) =
        TextStyle(fontFamily = InterFamily, fontSize = size.sp, lineHeight = (size + 5).sp, fontWeight = weight)
    return base.copy(
        titleSmall = s(13, FontWeight.SemiBold), titleMedium = s(14, FontWeight.SemiBold),
        bodyLarge = s(13), bodyMedium = s(13), bodySmall = s(12),
        labelLarge = s(13), labelMedium = s(12), labelSmall = s(11),
    )
}
