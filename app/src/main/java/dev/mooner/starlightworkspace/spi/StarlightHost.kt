package dev.mooner.starlightworkspace.spi

import dev.mooner.starlightworkspace.ApiInfo
import dev.mooner.starlightworkspace.DebugMessage
import dev.mooner.starlightworkspace.EventInfo
import dev.mooner.starlightworkspace.LanguageInfo
import dev.mooner.starlightworkspace.LogEntry
import java.io.File

/**
 * What the workspace reaches of StarLight. [SessionHost] is the running app; tests use
 * [dev.mooner.starlightworkspace.testing.FakeHost].
 */
interface StarlightHost {

    fun projects(): List<ProjectHandle>

    fun languages(): List<LanguageInfo>

    fun events(): List<EventInfo>

    fun apis(): List<ApiInfo>

    /** Creates a project in StarLight's project directory; throws if the name is taken or invalid. */
    fun createProject(name: String, languageId: String, eventIds: Set<String>): ProjectHandle
}

interface ProjectHandle {

    val id: String

    val name: String

    val languageId: String

    /** Path of the main script, relative to [directory]. */
    val mainScript: String

    val isEnabled: Boolean

    val isCompiled: Boolean

    val activeJobs: Int

    val directory: File

    /** Compiles the project; throws with the reason if it fails. */
    fun compile()

    /** False if StarLight refused, as it does for projects that aren't compiled. */
    fun setEnabled(enabled: Boolean): Boolean

    /** Ids of the allowed events, wildcards expanded. */
    fun allowedEvents(): Set<String>

    /** Replaces the allowed events with exactly [eventIds] and saves them. */
    fun setAllowedEvents(eventIds: Set<String>)

    /** The project's logs, oldest first. */
    fun logs(): List<LogEntry>

    /** Calls [listener] for each new log until the returned function is called. */
    fun addLogListener(listener: (LogEntry) -> Unit): () -> Unit

    /**
     * Sends [message] to the project's message event (`onMessage`) from a debug room, as StarLight's debug room does.
     * What the script sends to the room arrives at [onReply], and a failure of the event call at [onError]; both may
     * come later and on other threads. Throws if the project can't receive it now.
     */
    fun sendDebugMessage(message: DebugMessage, onReply: (String) -> Unit, onError: (Throwable) -> Unit)
}
