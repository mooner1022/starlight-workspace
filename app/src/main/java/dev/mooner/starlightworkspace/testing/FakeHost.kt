package dev.mooner.starlightworkspace.testing

import dev.mooner.starlightworkspace.ApiInfo
import dev.mooner.starlightworkspace.ArgInfo
import dev.mooner.starlightworkspace.DebugMessage
import dev.mooner.starlightworkspace.EventInfo
import dev.mooner.starlightworkspace.LanguageInfo
import dev.mooner.starlightworkspace.LogEntry
import dev.mooner.starlightworkspace.WorkspaceException
import dev.mooner.starlightworkspace.spi.ProjectHandle
import dev.mooner.starlightworkspace.spi.StarlightHost
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A StarLight without the app, for tests of this plugin and of the plugins using it. Projects live in [projectsDir].
 */
class FakeHost(
    val projectsDir: File,
) : StarlightHost {

    val projects: MutableList<FakeProject> = CopyOnWriteArrayList()

    val events: MutableList<EventInfo> = arrayListOf(
        EventInfo(MESSAGE_EVENT, "Message", "onMessage", null, listOf(ArgInfo("event", "dev.mooner.starlight.plugincore.chat.Message"))),
        EventInfo("starlight.project.compile", "Compile", "onStartCompile", null, emptyList()),
    )

    val apis: MutableList<ApiInfo> = arrayListOf()

    override fun projects(): List<ProjectHandle> = projects

    override fun languages(): List<LanguageInfo> =
        listOf(LanguageInfo("JS_NEON", "NeonJS", "js"))

    override fun events(): List<EventInfo> = events

    override fun apis(): List<ApiInfo> = apis

    override fun createProject(name: String, languageId: String, eventIds: Set<String>): ProjectHandle {
        if (projects.any { it.name.equals(name, ignoreCase = true) })
            throw WorkspaceException("A project named '$name' already exists")
        return addProject(name).also { it.allowed += eventIds }
    }

    /** Adds a project with an empty main script. */
    fun addProject(name: String): FakeProject {
        val dir = projectsDir.resolve(name).also(File::mkdirs)
        val project = FakeProject(name, dir)
        dir.resolve(project.mainScript).writeText("")
        projects += project
        return project
    }

    companion object {
        const val MESSAGE_EVENT = "starlight.message.create"
    }
}

class FakeProject(
    override val name: String,
    override val directory: File,
    override val id: String = "id-$name",
    override val mainScript: String = "$name.js",
) : ProjectHandle {

    override val languageId: String = "JS_NEON"

    @Volatile
    private var enabled: Boolean = true

    /** As in StarLight, a project that isn't compiled is off. */
    override val isEnabled: Boolean
        get() = enabled && isCompiled

    @Volatile
    override var isCompiled: Boolean = false

    override val activeJobs: Int = 0

    /** Thrown by [compile] when set. */
    @Volatile
    var compileError: Throwable? = null

    val allowed: MutableSet<String> = linkedSetOf(FakeHost.MESSAGE_EVENT)

    private val logs: MutableList<LogEntry> = CopyOnWriteArrayList()
    private val listeners: MutableList<(LogEntry) -> Unit> = CopyOnWriteArrayList()

    /** The project's onMessage: gets the message and a way to send to the room. Null: messages are refused. */
    @Volatile
    var onMessage: ((message: DebugMessage, send: (String) -> Unit) -> Unit)? = null

    fun log(message: String, type: String = "INFO") {
        val entry = LogEntry(type, name, message, System.currentTimeMillis())
        logs += entry
        listeners.forEach { it(entry) }
    }

    /** A failed compilation leaves the project not compiled, and so off, as in StarLight. */
    override fun compile() {
        log("compiling")
        compileError?.let {
            isCompiled = false
            throw it
        }
        isCompiled = true
    }

    /** Refuses while not compiled, as StarLight does. */
    override fun setEnabled(enabled: Boolean): Boolean {
        if (!isCompiled)
            return false
        this.enabled = enabled
        return true
    }

    override fun allowedEvents(): Set<String> = allowed.toSet()

    override fun setAllowedEvents(eventIds: Set<String>) {
        allowed.clear()
        allowed += eventIds
    }

    override fun logs(): List<LogEntry> = logs.toList()

    override fun addLogListener(listener: (LogEntry) -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    override fun sendDebugMessage(message: DebugMessage, onReply: (String) -> Unit, onError: (Throwable) -> Unit) {
        val onMessage = onMessage ?: throw WorkspaceException("Project '$name' doesn't receive messages")
        // Scripts run on the project's own threads.
        Thread {
            try {
                onMessage(message, onReply)
            } catch (e: Throwable) {
                onError(e)
            }
        }.start()
    }
}
