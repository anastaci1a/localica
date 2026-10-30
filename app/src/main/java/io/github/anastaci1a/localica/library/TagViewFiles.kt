package io.github.anastaci1a.localica.library

import java.io.File

/** Process-local ownership of temporary tag mirrors, shared by creation, cleanup and startup sweep. */
internal object TagViewFiles {
    private val gate = Any()
    private val active = mutableSetOf<File>()

    fun <T> use(directory: File, extension: String, reader: (File) -> T): T {
        val file = synchronized(gate) {
            val root = directory.absoluteFile.apply { mkdirs() }
            File.createTempFile("metadata-", ".$extension", root).also { active.add(it) }
        }
        try { return reader(file) }
        finally {
            synchronized(gate) {
                try { file.delete() }
                finally { active.remove(file) }
            }
        }
    }

    fun sweep(directory: File) = synchronized(gate) {
        directory.absoluteFile.listFiles()?.forEach { file -> if (file !in active) file.delete() }
    }
}
