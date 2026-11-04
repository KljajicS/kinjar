package app.kinjar.data

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

object InstallationIdentity {
    @Synchronized
    fun load(file: File): String {
        if (file.exists()) {
            val value = file.readText()
            require(value.length == 36 && UUID.fromString(value).toString() == value) { "Invalid installation identity" }
            return value
        }
        check(file.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
        val value = UUID.randomUUID().toString()
        val temporary = File(file.parentFile, "${file.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(value.toByteArray(Charsets.US_ASCII))
                stream.fd.sync()
            }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally {
            temporary.delete()
        }
        return value
    }
}
