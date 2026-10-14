package ksqlc.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import ksqlc.sql.TokenKind
import ksqlc.storage.ThemeMode

/** Every colour the UI uses, by role. Widgets read these instead of hard-coding colours. */
data class KsqlcColors(
    val dark: Boolean,
    // surfaces
    val windowBg: Color,        // the gaps between islands, status bar, floating pills
    val editorBg: Color,        // island body: editor, grid, trees
    val headerBg: Color,        // grid column headers
    val divider: Color,         // 1dp lines inside an island
    val border: Color,          // outline of pills, tabs and fields
    val toolbarBg: Color,       // action bars (editor toolbar); a tint in the light theme, same as the island in the dark one
    val dialogBg: Color,        // dialog windows
    // text
    val text: Color,            // UI text
    val textSecondary: Color,   // hints, captions
    val textMuted: Color,       // status bar, inline hints, disabled
    // interaction
    val accent: Color,          // filled buttons, focus ring
    val onAccent: Color,
    val accentIcon: Color,      // coloured glyphs (column type icons)
    val link: Color,            // text links
    val selection: Color,       // selected cell / focused selection
    val selectionInactive: Color,
    val hover: Color,
    val error: Color,
    val success: Color,
    val successOutline: Color,
    // editor
    val gutterText: Color,
    val gutterTextCurrent: Color,
    val currentLine: Color,
    val marginGuide: Color,
    val inlineHint: Color,
    val occurrence: Color,      // other uses of the identifier under the caret
    val warning: Color,         // warning squiggle
    val popupBg: Color,         // completion popup
    val popupSelection: Color,
    val pendingEdit: Color,     // grid: a cell edited but not sent
    val pendingInsert: Color,   // grid: a new row
    val pendingDelete: Color,   // grid: a row marked for deletion
    // grid
    val gridLine: Color,
    val rowNumber: Color,
    val nullText: Color,
    val gridText: Color,
    // syntax
    val synKeyword: Color,
    val synType: Color,
    val synString: Color,
    val synNumber: Color,
    val synComment: Color,
    val synIdent: Color,
    val synQuoted: Color,
    val synParam: Color,
    val synOperator: Color,
) {
    fun syntax(kind: TokenKind): Color = when (kind) {
        TokenKind.KEYWORD -> synKeyword
        TokenKind.TYPE -> synType
        TokenKind.STRING -> synString
        TokenKind.NUMBER -> synNumber
        TokenKind.COMMENT -> synComment
        TokenKind.QUOTED_IDENT -> synQuoted
        TokenKind.PARAM -> synParam
        TokenKind.OPERATOR, TokenKind.PUNCT -> synOperator
        TokenKind.IDENT, TokenKind.WHITESPACE -> synIdent
    }
}

// Both themes measured from the reference (docs/spec/ui-design.md). Not seen on screen, so kept at the usual IDE
// values: string/comment/identifier colours in dark, string/number/comment in light, dark dialog and link colours.
val DarkColors = KsqlcColors(
    dark = true,
    windowBg = Color(0xFF26282C), editorBg = Color(0xFF191A1C), headerBg = Color(0xFF292A2C),
    divider = Color(0xFF26282C), border = Color(0xFF40434A), toolbarBg = Color(0xFF191A1C), dialogBg = Color(0xFF26282C),
    text = Color(0xFFD1D3D9), textSecondary = Color(0xFF868A93), textMuted = Color(0xFF6F7276),
    accent = Color(0xFF3170E8), onAccent = Color(0xFFFFFFFF), accentIcon = Color(0xFF5F88F2), link = Color(0xFF7BA0F5),
    selection = Color(0xFF284180), selectionInactive = Color(0xFF33353B), hover = Color(0xFF2E3035),
    error = Color(0xFFF75464), success = Color(0xFF639260), successOutline = Color(0xFF4A774C),
    gutterText = Color(0xFF4C5058), gutterTextCurrent = Color(0xFFA1A3AA), currentLine = Color(0xFF1F2024),
    marginGuide = Color(0xFF323438), inlineHint = Color(0xFF676D74),
    occurrence = Color(0xFF2F3A52), warning = Color(0xFFE5B73B), popupBg = Color(0xFF26282C), popupSelection = Color(0xFF2F4A8C),
    pendingEdit = Color(0xFF243B63), pendingInsert = Color(0xFF1F3A2A), pendingDelete = Color(0xFF4A2327),
    gridLine = Color(0xFF323438), rowNumber = Color(0xFF4C5058), nullText = Color(0xFF7B7E85), gridText = Color(0xFFBCBEC4),
    synKeyword = Color(0xFFCF8E6D), synType = Color(0xFFCF8E6D), synString = Color(0xFF6AAB73), synNumber = Color(0xFF2AACB8),
    synComment = Color(0xFF7A7E85), synIdent = Color(0xFFBCBEC4), synQuoted = Color(0xFFBCBEC4), synParam = Color(0xFFC77DBB),
    synOperator = Color(0xFFBCBEC4),
)

val LightColors = KsqlcColors(
    dark = false,
    windowBg = Color(0xFFE9EAEE), editorBg = Color(0xFFFFFFFF), headerBg = Color(0xFFEFEFEF),
    divider = Color(0xFFE9EAEE), border = Color(0xFFD1D3D9), toolbarBg = Color(0xFFF7F8F9), dialogBg = Color(0xFFF7F8F9),
    text = Color(0xFF000000), textSecondary = Color(0xFF515151), textMuted = Color(0xFF7C7F86),
    accent = Color(0xFF3170E8), onAccent = Color(0xFFFFFFFF), accentIcon = Color(0xFF3574F0), link = Color(0xFF3A5DB5),
    selection = Color(0xFFD2DEFC), selectionInactive = Color(0xFFE9EAEE), hover = Color(0xFFDFE1E5),
    error = Color(0xFFDB3B4B), success = Color(0xFF67A36F), successOutline = Color(0xFFB8AAFB),
    gutterText = Color(0xFFA5A9B4), gutterTextCurrent = Color(0xFF6C6F7D), currentLine = Color(0xFFF5F8FE),
    marginGuide = Color(0xFFEBECF0), inlineHint = Color(0xFF868686),
    occurrence = Color(0xFFE3EBFC), warning = Color(0xFFC68A00), popupBg = Color(0xFFFFFFFF), popupSelection = Color(0xFFD2DEFC),
    pendingEdit = Color(0xFFD8E5FF), pendingInsert = Color(0xFFDDF2E3), pendingDelete = Color(0xFFFBDDE0),
    gridLine = Color(0xFFEBECF0), rowNumber = Color(0xFFAFB3C1), nullText = Color(0xFF767A85), gridText = Color(0xFF080808),
    synKeyword = Color(0xFF1233AF), synType = Color(0xFF1233AF), synString = Color(0xFF067D17), synNumber = Color(0xFF1750EB),
    synComment = Color(0xFF6B6F7A), synIdent = Color(0xFF080808), synQuoted = Color(0xFF080808), synParam = Color(0xFF871094),
    synOperator = Color(0xFF080808),
)

val LocalKsqlcColors = staticCompositionLocalOf { DarkColors }

/** `Ksqlc.colors` inside any composable under [KsqlcTheme]. */
object Ksqlc {
    val colors: KsqlcColors @Composable get() = LocalKsqlcColors.current
}

@Composable
fun isDark(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun KsqlcTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val c = if (isDark(mode)) DarkColors else LightColors
    val scheme = if (c.dark) darkColorScheme(
        primary = c.accent, onPrimary = c.onAccent, background = c.windowBg, onBackground = c.text,
        surface = c.editorBg, onSurface = c.text, surfaceVariant = c.headerBg, onSurfaceVariant = c.textSecondary,
        outline = c.border, outlineVariant = c.divider, error = c.error, secondaryContainer = c.selection, onSecondaryContainer = c.text,
    ) else lightColorScheme(
        primary = c.accent, onPrimary = c.onAccent, background = c.windowBg, onBackground = c.text,
        surface = c.editorBg, onSurface = c.text, surfaceVariant = c.headerBg, onSurfaceVariant = c.textSecondary,
        outline = c.border, outlineVariant = c.divider, error = c.error, secondaryContainer = c.selection, onSecondaryContainer = c.text,
    )
    CompositionLocalProvider(LocalKsqlcColors provides c) {
        MaterialTheme(colorScheme = scheme, typography = compactTypography(), content = content)
    }
}
