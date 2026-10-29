package ksqlc.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ksqlc.app.ui.*
import ksqlc.core.Language
import ksqlc.storage.Settings
import java.util.Locale

/** First-start language choice, shown in both languages because none is chosen yet. Tags: `lang-es`, `lang-en`, `lang-ok`. */
@Composable
fun LanguagePrompt(settings: Settings, onChosen: (Language) -> Unit) = KsqlcTheme(settings.themeMode) {
    val c = Ksqlc.colors
    var lang by remember { mutableStateOf(if (Locale.getDefault().language == "es") Language.ES else Language.EN) }
    Box(Modifier.fillMaxSize().background(c.windowBg), contentAlignment = Alignment.Center) {
        Column(Modifier.width(300.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Idioma / Language", fontSize = 14.sp, color = c.text)
            KRadio(lang == Language.ES, { lang = Language.ES }, "Español", Modifier.testTag("lang-es"))
            KRadio(lang == Language.EN, { lang = Language.EN }, "English (US)", Modifier.testTag("lang-en"))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextAction("OK / Aceptar", { onChosen(lang) }, Modifier.testTag("lang-ok"), primary = true)
            }
        }
    }
}
