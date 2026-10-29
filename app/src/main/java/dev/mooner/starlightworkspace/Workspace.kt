package dev.mooner.starlightworkspace

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlin.time.Duration

/**
 * StarLight's projects as tool plugins (MCP, IDE) work on them. Get it from [Workspaces.current].
 *
 * Methods that change something take an `actor`, such as "mcp" or "ide", which [changes] report so that each tool
 * can tell its own changes from the others'. Failures the caller can fix (a missing file, a path outside the project,
 * a stale hash) throw [WorkspaceException].
 */
interface Workspace {

    fun projects(): List<ProjectSummary>

    /** A project by name, id or case-insensitive name. */
    fun project(nameOrId: String): WorkspaceProject

    fun languages(): List<LanguageInfo>

    /** Every project event StarLight and its plugins registered. */
    fun eventCatalog(): List<EventInfo>

    /** Globals StarLight gives scripts. */
    fun apiCatalog(): List<ApiInfo>

    /** Logs of StarLight and its plugins since the workspace plugin started, oldest first. */
    fun appLogs(sinceMillis: Long): List<LogEntry>

    /** App logs as they are written. */
    fun appLogStream(): Flow<LogEntry>

    /** Creates a project with the language's default code for [eventIds], which are also allowed. */
    fun createProject(name: String, languageId: String, eventIds: Set<String>, actor: String): WorkspaceProject

    /** What changed in the workspace, by any tool or by StarLight itself. */
    val changes: SharedFlow<WorkspaceChange>
}

interface WorkspaceProject {

    val summary: ProjectSummary

    /** Files under [path] (relative to the project directory), recursively, at most [limit]. */
    fun listFiles(path: String, limit: Int): FileList

    fun readFile(path: String): FileContent

    /**
     * Writes a text file, creating directories as needed. With [expectedHash], fails with [ConflictException] unless
     * the file still has that hash (null hash: the file must not exist).
     */
    fun writeFile(path: String, text: String, expectedHash: String?, actor: String): FileContent

    /** Replaces [old], which must occur exactly once, with [new]. */
    fun editFile(path: String, old: String, new: String, expectedHash: String?, actor: String): FileContent

    fun deleteFile(path: String, actor: String)

    fun renameFile(from: String, to: String, actor: String)

    /** Lines matching [query] in text files, at most [limit]. */
    fun search(query: String, isRegex: Boolean, ignoreCase: Boolean, limit: Int): List<SearchHit>

    /** Compiles the project. A failed compilation is a result, not an exception. */
    suspend fun compile(actor: String): CompileResult

    /**
     * Turns the project on or off. A project that isn't compiled is always off (StarLight turns projects off when
     * they fail to compile), so turning one on fails until it is compiled.
     */
    fun setEnabled(enabled: Boolean, actor: String)

    /** Ids of the events the project receives, with wildcards expanded. */
    fun allowedEvents(): Set<String>

    /** Allows [allow] and disallows [disallow]; returns the events allowed afterwards. */
    fun updateEvents(allow: Set<String>, disallow: Set<String>, actor: String): Set<String>

    /** The project's logs since [sinceMillis], oldest first. */
    fun logs(sinceMillis: Long): List<LogEntry>

    /** The project's logs as they are written. */
    fun logStream(): Flow<LogEntry>

    /**
     * Sends a message to the project's message event (`onMessage`) from a debug room, as StarLight's debug room does,
     * and collects what the bot sends to the room until [wait] passes or it has been quiet for a moment after
     * answering.
     */
    suspend fun sendDebugMessage(message: DebugMessage, wait: Duration, actor: String): DebugReply
}

class ProjectSummary(
    val id: String,
    val name: String,
    val languageId: String,
    /** Path of the main script, relative to the project directory. */
    val mainScript: String,
    /** Never true while the project isn't compiled. */
    val isEnabled: Boolean,
    val isCompiled: Boolean,
    val activeJobs: Int,
)

class FileEntry(
    /** Relative to the project directory, with '/' separators. */
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val modifiedMillis: Long,
)

class FileList(
    val entries: List<FileEntry>,
    /** Whether more files were left out by the limit. */
    val isTruncated: Boolean,
)

class FileContent(
    val path: String,
    val text: String,
    /** SHA-256 hex of the file's bytes; pass it back as `expectedHash` to detect edits by others. */
    val hash: String,
    val modifiedMillis: Long,
)

class SearchHit(
    val path: String,
    /** 1-based. */
    val line: Int,
    val text: String,
)

class CompileResult(
    val isSuccess: Boolean,
    val error: String?,
    /** Where the error is, when the script engine tells it; [errorLine] and [errorColumn] are 1-based. */
    val errorPath: String?,
    val errorLine: Int?,
    val errorColumn: Int?,
    /** Logs written while compiling. */
    val logs: List<LogEntry>,
)

class LanguageInfo(
    val id: String,
    val name: String,
    val fileExtension: String,
)

class EventInfo(
    /** Such as `starlight.message.create`. */
    val id: String,
    val name: String,
    val functionName: String,
    val comment: String?,
    val args: List<ArgInfo>,
)

class ArgInfo(
    val name: String,
    /** Qualified Java class name. */
    val type: String,
)

class ApiInfo(
    /** The global's name. */
    val name: String,
    /** Qualified Java class name of the global's value. */
    val instanceClass: String,
    /** "OBJECT" or "CLASS". */
    val instanceType: String,
    val functions: List<ApiFunctionInfo>,
    val values: List<ApiValueInfo>,
)

class ApiFunctionInfo(
    val name: String,
    /** Qualified Java class names. */
    val args: List<String>,
    val returns: String,
)

class ApiValueInfo(
    val name: String,
    val type: String,
)

class LogEntry(
    /** VERBOSE, DEBUG, INFO, WARN, ERROR or CRITICAL. */
    val type: String,
    val tag: String,
    val message: String,
    val millis: Long,
)

/** A debug room message; null fields take the debug room settings of StarLight. */
class DebugMessage(
    val text: String,
    val sender: String?,
    val room: String?,
    val isGroupChat: Boolean?,
)

class DebugReply(
    /** What the bot sent to the room, in order. */
    val replies: List<String>,
    /** Why the event call failed, if it did. */
    val error: String?,
    /** Logs written while waiting. */
    val logs: List<LogEntry>,
)

/** Something that changed in the workspace. [actor] is the tool that changed it, or "starlight" for the app. */
sealed class WorkspaceChange {
    abstract val projectId: String
    abstract val actor: String

    class FileWritten(override val projectId: String, val path: String, val hash: String, override val actor: String) : WorkspaceChange()
    class FileDeleted(override val projectId: String, val path: String, override val actor: String) : WorkspaceChange()
    class FileRenamed(override val projectId: String, val from: String, val to: String, override val actor: String) : WorkspaceChange()
    class Compiled(override val projectId: String, val isSuccess: Boolean, override val actor: String) : WorkspaceChange()
    class EnabledChanged(override val projectId: String, val isEnabled: Boolean, override val actor: String) : WorkspaceChange()
    class EventsChanged(override val projectId: String, override val actor: String) : WorkspaceChange()
    class ProjectCreated(override val projectId: String, override val actor: String) : WorkspaceChange()
    class ProjectDeleted(override val projectId: String, override val actor: String) : WorkspaceChange()
    class DebugMessageSent(override val projectId: String, val message: String, val replies: List<String>, override val actor: String) : WorkspaceChange()
}

/** A failure the caller can fix, such as a missing file; [message] says what to do. */
open class WorkspaceException(message: String) : Exception(message)

/** The file changed since it was read. */
class ConflictException(
    val path: String,
    /** The file's hash now, or null if it doesn't exist. */
    val currentHash: String?,
) : WorkspaceException("$path was changed by someone else since it was read; read it again before writing")
