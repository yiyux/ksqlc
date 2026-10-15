package ksqlc.storage

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories

/** Writes [text] to [file] via a private temp file and an atomic rename, so readers never see a partial file. */
internal fun atomicWrite(file: Path, text: String) {
    val dir = file.toAbsolutePath().parent
    dir.createDirectories()
    val tmp = Files.createTempFile(dir, file.fileName.toString().substringBeforeLast('.'), ".tmp")
    runCatching { Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------")) }
    try {
        Files.writeString(tmp, text)
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } finally {
        Files.deleteIfExists(tmp)
    }
}
