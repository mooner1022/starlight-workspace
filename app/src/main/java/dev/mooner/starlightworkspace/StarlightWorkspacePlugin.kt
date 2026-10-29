package dev.mooner.starlightworkspace

import dev.mooner.starlight.plugincore.event.EventHandler
import dev.mooner.starlight.plugincore.event.Events
import dev.mooner.starlight.plugincore.event.on
import dev.mooner.starlight.plugincore.plugin.StarlightPlugin
import dev.mooner.starlightworkspace.spi.SessionHost
import dev.mooner.starlightworkspace.spi.toEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Provides the [Workspace] to tool plugins, which list `workspace@<version>` in their `dependency` and declare this
 * plugin's API jar as `compileOnly`. There is one workspace for all of them, so they see each other's changes.
 */
@Suppress("unused")
class StarlightWorkspacePlugin : StarlightPlugin() {

    private var scope: CoroutineScope? = null

    override fun onEnable() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        this.scope = scope
        val logs = LogBuffer()
        val workspace = DefaultWorkspace(SessionHost(), logs)

        EventHandler.on<Events.Log.Create>(scope) { logs.add(log.toEntry()) }
        EventHandler.on<Events.Project.Compile>(scope) {
            workspace.onAppChange(WorkspaceChange.Compiled(project.info.id.toString(), true, ACTOR_STARLIGHT))
        }
        EventHandler.on<Events.Project.Create>(scope) {
            workspace.onAppChange(WorkspaceChange.ProjectCreated(project.info.id.toString(), ACTOR_STARLIGHT))
        }
        EventHandler.on<Events.Project.Delete>(scope) {
            workspace.onAppChange(WorkspaceChange.ProjectDeleted(projectId.toString(), ACTOR_STARLIGHT))
        }
        Workspaces.set(workspace)
    }

    override fun onDisable() {
        Workspaces.set(null)
        scope?.cancel()
        scope = null
    }

    private companion object {
        /** The actor of changes StarLight makes itself. */
        const val ACTOR_STARLIGHT = "starlight"
    }
}
