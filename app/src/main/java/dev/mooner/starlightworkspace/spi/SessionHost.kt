package dev.mooner.starlightworkspace.spi

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.mooner.starlight.plugincore.Session
import dev.mooner.starlight.plugincore.api.ApiObject
import dev.mooner.starlight.plugincore.chat.ChatSender
import dev.mooner.starlight.plugincore.chat.DebugChatRoom
import dev.mooner.starlight.plugincore.chat.Message
import dev.mooner.starlight.plugincore.config.GlobalConfig
import dev.mooner.starlight.plugincore.logger.LogData
import dev.mooner.starlight.plugincore.project.Project
import dev.mooner.starlight.plugincore.project.event.ProjectEvent
import dev.mooner.starlight.plugincore.project.event.ProjectEventManager
import dev.mooner.starlightworkspace.ApiFunctionInfo
import dev.mooner.starlightworkspace.ApiInfo
import dev.mooner.starlightworkspace.ApiValueInfo
import dev.mooner.starlightworkspace.ArgInfo
import dev.mooner.starlightworkspace.DebugMessage
import dev.mooner.starlightworkspace.EventInfo
import dev.mooner.starlightworkspace.LanguageInfo
import dev.mooner.starlightworkspace.LogEntry
import dev.mooner.starlightworkspace.WorkspaceException
import java.io.File
import kotlin.reflect.KClass

/** StarLight as the running app has it. */
class SessionHost : StarlightHost {

    override fun projects(): List<ProjectHandle> =
        Session.projectManager.getProjects().map(::ProjectAdapter)

    override fun languages(): List<LanguageInfo> =
        Session.languageManager.getLanguages().map { LanguageInfo(it.id, it.name, it.fileExtension) }

    override fun events(): List<EventInfo> =
        ProjectEventManager.find(ALL_EVENTS).mapNotNull { eventClass ->
            val id = ProjectEventManager.validateAndGetEventID(eventClass) ?: return@mapNotNull null
            val event = eventClass.newEvent()
            EventInfo(
                id = id,
                name = event.name,
                functionName = event.functionName,
                comment = event.functionComment?.takeIf { it.isNotBlank() },
                args = event.argTypes.map { ArgInfo(it.name, it.type.java.name) },
            )
        }.sortedBy(EventInfo::id)

    override fun apis(): List<ApiInfo> =
        Session.apiManager.getApis().map { api ->
            ApiInfo(
                name = api.name,
                instanceClass = api.instanceClass.name,
                instanceType = api.instanceType.name,
                functions = api.objects.filterIsInstance<ApiObject.Function>().map { function ->
                    ApiFunctionInfo(function.name, function.args.map(Class<*>::getName), function.returns.name)
                },
                values = api.objects.filterIsInstance<ApiObject.Value>().map { ApiValueInfo(it.name, it.returns.name) },
            )
        }

    override fun createProject(name: String, languageId: String, eventIds: Set<String>): ProjectHandle {
        if (!PROJECT_NAME_REGEX.matches(name))
            throw WorkspaceException("Invalid project name '$name': use letters, digits, Hangul, '-' and '_'")
        val manager = Session.projectManager
        if (manager.getProjectByName(name, true) != null)
            throw WorkspaceException("A project named '$name' already exists")
        val language = Session.languageManager.getLanguage(languageId, false)
            ?: throw WorkspaceException("No language '$languageId'")
        val events = eventIds.associateWith { id ->
            val eventClass = ProjectEventManager.findFirst(id) ?: throw WorkspaceException("No event '$id'")
            eventClass.newEvent()
        }
        // As StarLight's new project dialog does.
        manager.newProject(events = events) {
            this.name = name
            mainScript = "$name.${language.fileExtension}"
            this.languageId = language.id
        }
        return ProjectAdapter(manager.getProjectByName(name) ?: error("Project $name was not created"))
    }

    private class ProjectAdapter(
        private val project: Project,
    ) : ProjectHandle {

        override val id: String
            get() = project.info.id.toString()

        override val name: String
            get() = project.info.name

        override val languageId: String
            get() = project.info.languageId

        override val mainScript: String
            get() = project.info.mainScript

        override val isEnabled: Boolean
            get() = project.info.isEnabled

        override val isCompiled: Boolean
            get() = project.isCompiled

        override val activeJobs: Int
            get() = project.activeJobs()

        override val directory: File
            get() = project.directory

        override fun compile() {
            check(project.compile(true)) { "Compilation failed" }
        }

        override fun setEnabled(enabled: Boolean): Boolean =
            project.setEnabled(enabled)

        override fun allowedEvents(): Set<String> =
            ProjectEventManager.filterAllowedEvents(project.info.allowedEventIds)

        override fun setAllowedEvents(eventIds: Set<String>) {
            // Saving the info makes the project reload its allowed events.
            Session.projectManager.updateProjectInfo(project.info.name, true) {
                allowedEventIds.clear()
                allowedEventIds.addAll(eventIds)
            }
        }

        override fun logs(): List<LogEntry> =
            project.logger.logs.map(LogData::toEntry)

        override fun addLogListener(listener: (LogEntry) -> Unit): () -> Unit {
            val logger = project.logger
            val key = logger.addOnLogCreateListener { listener(it.toEntry()) }
            return { logger.removeOnLogCreateListener(key) }
        }

        override fun sendDebugMessage(message: DebugMessage, onReply: (String) -> Unit, onError: (Throwable) -> Unit) {
            if (!project.isCompiled)
                throw WorkspaceException("Project '$name' is not compiled")
            val event = DebugRoom.messageEvent()
            val eventId = ProjectEventManager.validateAndGetEventID(event)
                ?: error("StarLight's message event is not registered")
            if (!project.isEventCallAllowed(eventId))
                throw WorkspaceException("Project '$name' doesn't receive messages: allow the event $eventId")
            val room = GlobalConfig.category("d_room")
            val data = Message(
                message = message.text,
                image = null,
                sender = ChatSender(
                    name = message.sender ?: GlobalConfig.category("d_user").getString("name", DebugRoom.DEFAULT_SENDER),
                    id = null,
                    profileBitmap = DebugRoom.profileBitmap(),
                ),
                room = DebugChatRoom(
                    id = room.getString("id") ?: DebugRoom.DEFAULT_ROOM_ID,
                    name = message.room ?: room.getString("name", DebugRoom.DEFAULT_ROOM),
                    isGroupChat = message.isGroupChat ?: room.getBoolean("is_group_chat", false),
                    onSend = onReply,
                    onMarkAsRead = {},
                ),
                packageName = room.getString("package", DebugRoom.PACKAGE_KAKAO_TALK),
                hasMention = false,
                chatLogId = -1L,
            )
            project.callFunction(event.newEvent().functionName, arrayOf(data), onError)
        }
    }

    /** What StarLight's debug room screen (app/ui/debugroom/DebugRoomFragment) uses. */
    private object DebugRoom {

        /** The app's message event, not part of PluginCore. */
        private const val MESSAGE_EVENT_CLASS = "dev.mooner.starlight.listener.event.ProjectOnMessageEvent"

        const val PACKAGE_KAKAO_TALK = "com.kakao.talk"
        const val DEFAULT_SENDER = "debug_sender"
        const val DEFAULT_ROOM = "undefined"
        const val DEFAULT_ROOM_ID = "workspace_debug_room"

        @Suppress("UNCHECKED_CAST")
        fun messageEvent(): KClass<out ProjectEvent> =
            Class.forName(MESSAGE_EVENT_CLASS, true, Session::class.java.classLoader).kotlin as KClass<out ProjectEvent>

        @Volatile
        private var blankProfile: Bitmap? = null

        /** The profile image set in the debug room, or a blank one. */
        fun profileBitmap(): Bitmap =
            GlobalConfig.category("d_user").getString("profile_image_path")
                ?.let { path -> runCatching { BitmapFactory.decodeFile(path) }.getOrNull() }
                ?: blankProfile
                ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).also { blankProfile = it }
    }

    private companion object {
        const val ALL_EVENTS = "*"

        // The rule of StarLight's new project dialog.
        val PROJECT_NAME_REGEX = Regex("^[-_\\dA-Za-zㄱ-ㅎㅏ-ㅣ가-힣]+$")

        fun KClass<out ProjectEvent>.newEvent(): ProjectEvent =
            java.getDeclaredConstructor().newInstance()
    }
}

fun LogData.toEntry(): LogEntry =
    LogEntry(type.name, tag.orEmpty(), message, millis)
