package ksqlc.app

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File

/** Renders [this] to `app/build/snapshots/<name>.png` so the real pixels can be inspected without a screen. */
fun SemanticsNodeInteraction.savePng(name: String): File {
    val bitmap = captureToImage().asSkiaBitmap()
    val data = Image.makeFromBitmap(bitmap).encodeToData(EncodedImageFormat.PNG) ?: error("no se pudo codificar el PNG")
    return File("build/snapshots/$name.png").also { it.parentFile.mkdirs(); it.writeBytes(data.bytes) }
}

fun ComposeContentTestRule.snapshot(name: String): File = onRoot().savePng(name)
