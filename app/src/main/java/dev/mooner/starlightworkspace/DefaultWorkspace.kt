package dev.mooner.starlightworkspace

import dev.mooner.starlightworkspace.spi.ProjectHandle
import dev.mooner.starlightworkspace.spi.StarlightHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

/** The [Workspace] over a [StarlightHost]; tests build one over a fake host. */
class DefaultWorkspace(
    private val host: StarlightHost,
    private val appLogBuffer: LogBuffer = LogBuffer(),
) : Workspace {

    private val mChanges = MutableSharedFlow<WorkspaceChange>(extraBufferCapacity = CHANGE_BUFFER)
    override val changes: SharedFlow<WorkspaceChange> = mChanges.asSharedFlow()

    /** Serializes check-then-write of files, so two tools can't both pass the same hash check. */
    private val fileLock = Any()

    /** When each project was last compiled through the workspace, to tell StarLight's own compile events apart. */
    private val compiledAt: MutableMap<String, Long> = ConcurrentHashMap()

    override fun projects(): List<ProjectSummary> =
        host.projects().map { it.summary() }.sortedBy { it.name.lowercase() }

    override fun project(nameOrId: String): WorkspaceProject {
        val projects = host.projects()
        val handle = projects.firstOrNull { it.name == nameOrId }
            ?: projects.firstOrNull { it.id == nameOrId }
            ?: projects.firstOrNull { it.name.equals(nameOrId, ignoreCase = true) }
            ?: throw WorkspaceException("No project named '$nameOrId'")
        return Project(handle)
    }

    override fun languages(): List<LanguageInfo> =
        host.languages()

    override fun eventCatalog(): List<EventInfo> =
        host.events()

    override fun apiCatalog(): List<ApiInfo> =
        host.apis()

    override fun appLogs(sinceMillis: Long): List<LogEntry> =
        appLogBuffer.snapshot().filter { it.millis >= sinceMillis }

    override fun appLogStream(): Flow<LogEntry> =
        appLogBuffer.stream

    override fun createProject(name: String, languageId: String, eventIds: Set<String>, actor: String): WorkspaceProject {
        val handle = host.createProject(name, languageId, eventIds)
        emit(WorkspaceChange.ProjectCreated(handle.id, actor))
        return Project(handle)
    }

    /** Reports a change StarLight made itself, such as a compilation from its editor. */
    fun onAppChange(change: WorkspaceChange) {
        if (change is WorkspaceChange.Compiled) {
            val last = compiledAt[change.projectId]
            if (last != null && System.currentTimeMillis() - last < OWN_COMPILE_WINDOW_MILLIS)
                return
        }
        emit(change)
    }

    private fun emit(change: WorkspaceChange) {
        mChanges.tryEmit(change)
    }

    private inner class Project(
        private val handle: ProjectHandle,
    ) : WorkspaceProject {

        private val root: File
            get() = handle.directory.canonicalFile

        override val summary: ProjectSummary
            get() = handle.summary()

        override fun listFiles(path: String, limit: Int): FileList {
            val dir = resolve(path)
            if (!dir.isDirectory)
                throw WorkspaceException("Not a directory: $path")
            val root = root
            val files = dir.walkTopDown()
                .filter { it != dir }
                .take(limit + 1)
                .map { FileEntry(it.relativeTo(root).invariantSeparatorsPath, it.isDirectory, if (it.isFile) it.length() else 0, it.lastModified()) }
                .toList()
            return FileList(files.take(limit), isTruncated = files.size > limit)
        }

        override fun readFile(path: String): FileContent {
            val file = resolve(path)
            if (!file.isFile)
                throw WorkspaceException("No such file: ${relative(file)}")
            if (file.length() > MAX_TEXT_BYTES)
                throw WorkspaceException("${relative(file)} is larger than ${MAX_TEXT_BYTES / 1024}KB")
            return contentOf(file, file.readBytes())
        }

        override fun writeFile(path: String, text: String, expectedHash: String?, actor: String): FileContent {
            val file = resolve(path)
            if (file.isDirectory)
                throw WorkspaceException("Is a directory: $path")
            val content = synchronized(fileLock) {
                checkHash(file, expectedHash)
                file.parentFile?.mkdirs()
                val bytes = text.toByteArray()
                file.writeBytes(bytes)
                contentOf(file, bytes)
            }
            emit(WorkspaceChange.FileWritten(handle.id, content.path, content.hash, actor))
            return content
        }

        override fun editFile(path: String, old: String, new: String, expectedHash: String?, actor: String): FileContent {
            if (old.isEmpty())
                throw WorkspaceException("The text to replace is empty")
            val file = resolve(path)
            val content = synchronized(fileLock) {
                if (!file.isFile)
                    throw WorkspaceException("No such file: $path")
                checkHash(file, expectedHash)
                val text = file.readText()
                val first = text.indexOf(old)
                if (first < 0)
                    throw WorkspaceException("The text to replace was not found in $path")
                if (text.indexOf(old, first + 1) >= 0)
                    throw WorkspaceException("The text to replace occurs more than once in $path; include more context")
                val bytes = (text.substring(0, first) + new + text.substring(first + old.length)).toByteArray()
                file.writeBytes(bytes)
                contentOf(file, bytes)
            }
            emit(WorkspaceChange.FileWritten(handle.id, content.path, content.hash, actor))
            return content
        }

        override fun deleteFile(path: String, actor: String) {
            val file = resolve(path)
            if (!file.isFile)
                throw WorkspaceException("No such file: $path")
            if (!file.delete())
                throw WorkspaceException("Could not delete $path")
            emit(WorkspaceChange.FileDeleted(handle.id, relative(file), actor))
        }

        override fun renameFile(from: String, to: String, actor: String) {
            val source = resolve(from)
            val target = resolve(to)
            if (!source.isFile)
                throw WorkspaceException("No such file: $from")
            if (target.exists())
                throw WorkspaceException("$to already exists")
            target.parentFile?.mkdirs()
            if (!source.renameTo(target))
                throw WorkspaceException("Could not rename $from to $to")
            emit(WorkspaceChange.FileRenamed(handle.id, relative(source), relative(target), actor))
        }

        override fun search(query: String, isRegex: Boolean, ignoreCase: Boolean, limit: Int): List<SearchHit> {
            if (query.isEmpty())
                throw WorkspaceException("Empty search")
            val options = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
            val regex = try {
                Regex(if (isRegex) query else Regex.escape(query), options)
            } catch (e: IllegalArgumentException) {
                throw WorkspaceException("Invalid regular expression: ${e.message}")
            }
            val hits = arrayListOf<SearchHit>()
            for (file in root.walkTopDown()) {
                if (!file.isFile || file.length() > MAX_TEXT_BYTES || isBinary(file))
                    continue
                file.useLines { lines ->
                    for ((i, line) in lines.withIndex()) {
                        if (regex.containsMatchIn(line)) {
                            hits += SearchHit(relative(file), i + 1, line.take(MAX_HIT_LENGTH))
                            if (hits.size >= limit)
                                return hits
                        }
                    }
                }
            }
            return hits
        }

        override suspend fun compile(actor: String): CompileResult = withContext(Dispatchers.IO) {
            val startMillis = System.currentTimeMillis()
            compiledAt[handle.id] = startMillis
            val error = try {
                handle.compile()
                null
            } catch (e: Exception) {
                e
            }
            compiledAt[handle.id] = System.currentTimeMillis()
            val location = error?.let(ErrorLocations::find)
            val result = CompileResult(
                isSuccess = error == null,
                error = error?.let { it.message ?: it.toString() },
                errorPath = location?.source?.let(::sourcePath),
                errorLine = location?.line,
                errorColumn = location?.column,
                logs = handle.logs().filter { it.millis >= startMillis },
            )
            emit(WorkspaceChange.Compiled(handle.id, result.isSuccess, actor))
            result
        }

        override fun setEnabled(enabled: Boolean, actor: String) {
            if (!handle.setEnabled(enabled)) {
                // A project that isn't compiled is always off, so turning it off has nothing to do.
                if (!handle.isCompiled && !enabled)
                    return
                throw WorkspaceException(
                    if (!handle.isCompiled) "Project '${handle.name}' is not compiled: compile it before turning it on"
                    else "StarLight didn't turn project '${handle.name}' ${if (enabled) "on" else "off"}"
                )
            }
            emit(WorkspaceChange.EnabledChanged(handle.id, handle.isEnabled, actor))
        }

        override fun allowedEvents(): Set<String> =
            handle.allowedEvents()

        override fun updateEvents(allow: Set<String>, disallow: Set<String>, actor: String): Set<String> {
            val known = host.events().mapTo(hashSetOf(), EventInfo::id)
            val unknown = (allow + disallow) - known
            if (unknown.isNotEmpty())
                throw WorkspaceException("Unknown events: ${unknown.joinToString()}")
            // Allowed ids may be wildcards such as starlight.*; they are stored expanded so that one can be removed.
            val events = handle.allowedEvents() + allow - disallow
            handle.setAllowedEvents(events)
            emit(WorkspaceChange.EventsChanged(handle.id, actor))
            return handle.allowedEvents()
        }

        override fun logs(sinceMillis: Long): List<LogEntry> =
            handle.logs().filter { it.millis >= sinceMillis }

        override fun logStream(): Flow<LogEntry> = callbackFlow {
            val remove = handle.addLogListener { trySend(it) }
            awaitClose(remove)
        }

        override suspend fun sendDebugMessage(message: DebugMessage, wait: Duration, actor: String): DebugReply {
            val events = Channel<Any>(Channel.UNLIMITED)
            val startMillis = System.currentTimeMillis()
            withContext(Dispatchers.IO) {
                handle.sendDebugMessage(message, onReply = { events.trySend(it) }, onError = { events.trySend(it) })
            }

            val replies = arrayListOf<String>()
            var error: Throwable? = null
            val deadline = startMillis + wait.inWholeMilliseconds
            while (true) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0)
                    break
                // A bot may send several messages, so a reply only ends the wait after a quiet moment.
                val isAnswered = replies.isNotEmpty() || error != null
                val event = withTimeoutOrNull(if (isAnswered) minOf(remaining, REPLY_QUIET_MILLIS) else remaining) { events.receive() }
                    ?: break
                when (event) {
                    is String    -> replies += event
                    is Throwable -> error = event
                }
            }
            events.close()

            emit(WorkspaceChange.DebugMessageSent(handle.id, message.text, replies.toList(), actor))
            return DebugReply(
                replies = replies,
                error = error?.let { it.message ?: it.toString() },
                logs = handle.logs().filter { it.millis >= startMillis },
            )
        }

        /** [path] inside the project directory; anything leading outside of it is refused. */
        private fun resolve(path: String): File {
            val root = root
            if (File(path).isAbsolute)
                throw WorkspaceException("Path must be relative to the project directory: $path")
            val file = root.resolve(path).canonicalFile
            if (file != root && !file.path.startsWith(root.path + File.separator))
                throw WorkspaceException("Path is outside the project directory: $path")
            return file
        }

        private fun relative(file: File): String =
            file.relativeTo(root).invariantSeparatorsPath

        private fun contentOf(file: File, bytes: ByteArray): FileContent =
            FileContent(relative(file), bytes.decodeToString(), sha256Hex(bytes), file.lastModified())

        /** `null` checks nothing; an empty hash means the file must not exist yet. */
        private fun checkHash(file: File, expectedHash: String?) {
            if (expectedHash == null)
                return
            val current = if (file.isFile) sha256Hex(file.readBytes()) else null
            if (current != expectedHash.ifEmpty { null })
                throw ConflictException(relative(file), current)
        }

        /** A script path an engine reported, relative to the project if it is inside it. */
        private fun sourcePath(source: String): String {
            val file = File(source)
            if (!file.isAbsolute)
                return source.removePrefix("./")
            val canonical = file.canonicalFile
            return if (canonical.path.startsWith(root.path + File.separator)) relative(canonical) else source
        }
    }

    companion object {

        const val MAX_TEXT_BYTES = 2L * 1024 * 1024

        private const val CHANGE_BUFFER = 256
        private const val MAX_HIT_LENGTH = 300
        private const val REPLY_QUIET_MILLIS = 1_000L
        private const val OWN_COMPILE_WINDOW_MILLIS = 3_000L

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        private fun ProjectHandle.summary(): ProjectSummary =
            ProjectSummary(id, name, languageId, mainScript, isEnabled, isCompiled, activeJobs)

        private fun isBinary(file: File): Boolean =
            file.inputStream().use { input ->
                val buffer = ByteArray(BINARY_SNIFF_BYTES)
                val read = input.read(buffer)
                (0 until maxOf(read, 0)).any { buffer[it] == 0.toByte() }
            }

        private const val BINARY_SNIFF_BYTES = 8 * 1024
    }
}
