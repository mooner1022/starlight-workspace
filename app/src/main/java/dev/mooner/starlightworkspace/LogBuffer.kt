package dev.mooner.starlightworkspace

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.ArrayDeque

/** The latest [capacity] logs. StarLight keeps no list of app logs, so they are collected from its log events. */
class LogBuffer(
    private val capacity: Int = 1000,
) {

    private val logs = ArrayDeque<LogEntry>(capacity)

    private val mStream = MutableSharedFlow<LogEntry>(extraBufferCapacity = 256)
    val stream: SharedFlow<LogEntry> = mStream.asSharedFlow()

    fun add(entry: LogEntry) {
        synchronized(logs) {
            if (logs.size == capacity)
                logs.removeFirst()
            logs.addLast(entry)
        }
        mStream.tryEmit(entry)
    }

    /** Oldest first. */
    fun snapshot(): List<LogEntry> =
        synchronized(logs) { logs.toList() }
}
