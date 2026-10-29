package dev.mooner.starlightworkspace

import dev.mooner.neonjs.NeonSyntaxException
import dev.mooner.starlightworkspace.testing.FakeHost
import dev.mooner.starlightworkspace.testing.FakeProject
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mozilla.javascript.EvaluatorException
import kotlin.time.Duration.Companion.seconds

class DefaultWorkspaceTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var host: FakeHost
    private lateinit var bot: FakeProject
    private lateinit var workspace: DefaultWorkspace

    @Before
    fun setUp() {
        host = FakeHost(folder.newFolder("projects"))
        bot = host.addProject("bot")
        bot.directory.resolve("bot.js").writeText("// main")
        bot.directory.resolve("lib").mkdirs()
        bot.directory.resolve("lib/util.js").writeText("export const a = 1;\n// TODO util")
        folder.root.resolve("secret.txt").writeText("secret")
        workspace = DefaultWorkspace(host)
    }

    @Test
    fun findsProjects() {
        assertEquals("bot", workspace.project("bot").summary.name)
        assertEquals("bot", workspace.project("id-bot").summary.name)
        assertEquals("bot", workspace.project("BOT").summary.name)
        assertThrows(WorkspaceException::class.java) { workspace.project("nope") }
    }

    @Test
    fun readsAndListsFiles() {
        val project = workspace.project("bot")
        assertEquals("// main", project.readFile("bot.js").text)
        val files = project.listFiles(".", 100)
        assertEquals(listOf("bot.js", "lib", "lib/util.js"), files.entries.map { it.path }.sorted())
        assertFalse(files.isTruncated)
        assertTrue(project.listFiles(".", 1).isTruncated)
    }

    @Test
    fun staysInsideTheProjectDirectory() {
        val project = workspace.project("bot")
        for (path in listOf("../secret.txt", "lib/../../secret.txt", folder.root.resolve("secret.txt").path))
            assertThrows(path, WorkspaceException::class.java) { project.readFile(path) }
        assertThrows(WorkspaceException::class.java) { project.writeFile("../evil.js", "x", null, "test") }
        assertFalse(folder.root.resolve("evil.js").exists())
        assertThrows(WorkspaceException::class.java) { project.renameFile("bot.js", "../moved.js", "test") }
    }

    @Test
    fun detectsConflicts() {
        val project = workspace.project("bot")
        val read = project.readFile("bot.js")
        val written = project.writeFile("bot.js", "// mine", read.hash, "ide")

        // Someone else wrote meanwhile.
        val conflict = assertThrows(ConflictException::class.java) { project.writeFile("bot.js", "// theirs", read.hash, "mcp") }
        assertEquals(written.hash, conflict.currentHash)
        assertEquals("// mine", project.readFile("bot.js").text)

        // An empty hash creates only.
        project.writeFile("new.js", "1", "", "ide")
        assertThrows(ConflictException::class.java) { project.writeFile("new.js", "2", "", "ide") }
    }

    @Test
    fun editsFiles() {
        val project = workspace.project("bot")
        project.editFile("lib/util.js", "a = 1", "a = 2", null, "mcp")
        assertEquals("export const a = 2;\n// TODO util", project.readFile("lib/util.js").text)
        assertThrows(WorkspaceException::class.java) { project.editFile("lib/util.js", "missing", "x", null, "mcp") }
        project.writeFile("twice.js", "x x", null, "mcp")
        assertThrows(WorkspaceException::class.java) { project.editFile("twice.js", "x", "y", null, "mcp") }
    }

    @Test
    fun renamesAndDeletes() {
        val project = workspace.project("bot")
        project.renameFile("lib/util.js", "util/main.js", "ide")
        assertTrue(bot.directory.resolve("util/main.js").isFile)
        project.deleteFile("util/main.js", "ide")
        assertFalse(bot.directory.resolve("util/main.js").exists())
        assertThrows(WorkspaceException::class.java) { project.deleteFile("lib", "ide") }
    }

    @Test
    fun searches() {
        val project = workspace.project("bot")
        val hits = project.search("todo", isRegex = false, ignoreCase = true, limit = 10)
        assertEquals(listOf("lib/util.js:2"), hits.map { "${it.path}:${it.line}" })
        assertEquals(1, project.search("const \\w+ =", isRegex = true, ignoreCase = false, limit = 10).size)
        assertThrows(WorkspaceException::class.java) { project.search("(", isRegex = true, ignoreCase = false, limit = 10) }
    }

    @Test
    fun compilesAndLocatesErrors() = runBlocking {
        val project = workspace.project("bot")
        val ok = project.compile("ide")
        assertTrue(ok.isSuccess)
        assertTrue(ok.logs.any { it.message == "compiling" })

        bot.compileError = IllegalStateException("Compile failed", NeonSyntaxException("Unexpected token", 3, 7, "lib/util.js"))
        val neon = project.compile("ide")
        assertFalse(neon.isSuccess)
        assertEquals("Compile failed", neon.error)
        assertEquals(listOf("lib/util.js", 3, 7), listOf(neon.errorPath, neon.errorLine, neon.errorColumn))

        bot.compileError = EvaluatorException("missing ;", bot.directory.resolve("bot.js").path, 5)
        val rhino = project.compile("ide")
        assertEquals(listOf("bot.js", 5), listOf(rhino.errorPath, rhino.errorLine))

        bot.compileError = IllegalStateException("no location")
        assertNull(project.compile("ide").errorLine)
    }

    @Test
    fun turnsOnOnlyCompiledProjects() = runBlocking<Unit> {
        val project = workspace.project("bot")
        // Not compiled: off, and only turning it on is refused.
        assertFalse(project.summary.isEnabled)
        val refused = assertThrows(WorkspaceException::class.java) { project.setEnabled(true, "ide") }
        assertTrue(refused.message!!.contains("not compiled"))
        project.setEnabled(false, "ide")

        project.compile("ide")
        assertTrue(project.summary.isEnabled)
        project.setEnabled(false, "ide")
        assertFalse(project.summary.isEnabled)
        project.setEnabled(true, "ide")
        assertTrue(project.summary.isEnabled)

        // A failed compilation turns it off.
        bot.compileError = IllegalStateException("SyntaxError")
        project.compile("ide")
        assertFalse(project.summary.isEnabled)
    }

    @Test
    fun updatesEvents() {
        val project = workspace.project("bot")
        assertEquals(setOf(FakeHost.MESSAGE_EVENT), project.allowedEvents())
        val events = project.updateEvents(setOf("starlight.project.compile"), setOf(FakeHost.MESSAGE_EVENT), "ide")
        assertEquals(setOf("starlight.project.compile"), events)
        assertThrows(WorkspaceException::class.java) { project.updateEvents(setOf("no.such.event"), emptySet(), "ide") }
    }

    @Test
    fun createsProjects() {
        val project = workspace.createProject("new", "JS_NEON", setOf(FakeHost.MESSAGE_EVENT), "mcp")
        assertEquals("new", project.summary.name)
        assertEquals(setOf(FakeHost.MESSAGE_EVENT), project.allowedEvents())
        assertThrows(WorkspaceException::class.java) { workspace.createProject("NEW", "JS_NEON", emptySet(), "mcp") }
    }

    @Test
    fun sendsDebugMessages() = runBlocking<Unit> {
        bot.onMessage = { message, send ->
            send("${message.sender}: ${message.text}")
            Thread.sleep(100)
            send("second")
            bot.log("handled")
        }
        val project = workspace.project("bot")
        val reply = project.sendDebugMessage(DebugMessage("!ping", "me", null, null), 5.seconds, "mcp")
        assertEquals(listOf("me: !ping", "second"), reply.replies)
        assertTrue(reply.logs.any { it.message == "handled" })

        bot.onMessage = { _, _ -> throw IllegalStateException("ReferenceError: x") }
        assertEquals("ReferenceError: x", project.sendDebugMessage(DebugMessage("hi", null, null, null), 5.seconds, "mcp").error)

        bot.onMessage = { _, _ -> }
        assertTrue(project.sendDebugMessage(DebugMessage("hi", null, null, null), 1.seconds, "mcp").replies.isEmpty())

        bot.onMessage = null
        assertThrows(WorkspaceException::class.java) {
            runBlocking { project.sendDebugMessage(DebugMessage("hi", null, null, null), 1.seconds, "mcp") }
        }
    }

    @Test
    fun reportsChanges() = runBlocking {
        val changes = async(start = CoroutineStart.UNDISPATCHED) { workspace.changes.take(3).toList() }
        val project = workspace.project("bot")
        project.writeFile("a.js", "1", null, "ide")
        project.compile("ide")
        project.setEnabled(false, "mcp")
        val received = withTimeout(5_000) { changes.await() }
        assertEquals(listOf("FileWritten:ide", "Compiled:ide", "EnabledChanged:mcp"), received.map { "${it::class.simpleName}:${it.actor}" })
        assertEquals("a.js", (received[0] as WorkspaceChange.FileWritten).path)
    }

    @Test
    fun ownCompilationsAreNotReportedTwice() = runBlocking {
        workspace.project("bot").compile("ide")
        val later = async(start = CoroutineStart.UNDISPATCHED) { workspace.changes.first() }
        // StarLight reports the same compilation, then a file is written.
        workspace.onAppChange(WorkspaceChange.Compiled("id-bot", true, "starlight"))
        workspace.project("bot").writeFile("b.js", "1", null, "ide")
        assertTrue(withTimeout(5_000) { later.await() } is WorkspaceChange.FileWritten)
    }

    @Test
    fun streamsLogs() = runBlocking {
        val logs = async(start = CoroutineStart.UNDISPATCHED) { workspace.project("bot").logStream().take(2).toList() }
        // The flow subscribes once its producer runs.
        delay(100)
        bot.log("one")
        bot.log("two")
        assertEquals(listOf("one", "two"), withTimeout(5_000) { logs.await() }.map { it.message })
    }
}
