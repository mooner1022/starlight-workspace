package dev.mooner.starlightworkspace

/** Where tool plugins get the [Workspace]. */
object Workspaces {

    /**
     * Raised with every addition to the API. StarLight checks only the major and minor version of a dependency, so a
     * plugin built against a newer patch release checks this in its `onEnable`.
     */
    const val API_LEVEL = 1

    @Volatile
    private var instance: Workspace? = null

    /** The workspace while the workspace plugin is enabled. */
    val current: Workspace
        get() = instance ?: throw IllegalStateException("The workspace plugin is not enabled")

    /** The API level of the installed workspace plugin; compare with the level a plugin was built against. */
    val apiLevel: Int
        get() = API_LEVEL

    internal fun set(workspace: Workspace?) {
        instance = workspace
    }
}
