package io.unisondroid.app.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class JsonStore(private val dir: File) {

    private val mutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend inline fun <reified T> read(name: String): T? = read(name, serializer<T>())

    suspend inline fun <reified T> write(name: String, value: T): Unit = write(name, value, serializer<T>())

    @PublishedApi
    internal suspend fun <T> read(name: String, serializer: KSerializer<T>): T? = mutex.withLock {
        val file = fileFor(name)
        if (!file.isFile) {
            null
        } else {
            json.decodeFromString(serializer, file.readText())
        }
    }

    @PublishedApi
    internal suspend fun <T> write(name: String, value: T, serializer: KSerializer<T>) {
        mutex.withLock {
            dir.mkdirs()
            val target = fileFor(name)
            val temp = File(dir, "${target.name}.tmp")
            temp.writeText(json.encodeToString(serializer, value))
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    private fun fileFor(name: String): File = File(dir, "$name.json")
}
