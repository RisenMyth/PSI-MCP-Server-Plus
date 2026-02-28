package com.github.risenmyth.psimcpserverplus.mcp.sse

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class SseSessionState(
    val id: String,
    val projectPath: String,
    queueSize: Int,
) {
    @Volatile
    var initialized: Boolean = false

    @Volatile
    var lastActivity: Long = System.currentTimeMillis()

    private val streamAttached = AtomicBoolean(false)
    private val eventQueue = ArrayBlockingQueue<String>(queueSize)
    private val eventCounter = AtomicLong(0L)
    private val droppedEventCount = AtomicLong(0L)
    private val eventHistory = ConcurrentSkipListMap<Long, String>()

    fun attachStream(): Boolean = streamAttached.compareAndSet(false, true)

    fun detachStream() {
        streamAttached.set(false)
    }

    fun streamAttached(): Boolean = streamAttached.get()

    fun pollEvent(timeout: Long, unit: TimeUnit): String? = eventQueue.poll(timeout, unit)

    fun nextEventId(): Long = eventCounter.incrementAndGet()

    fun offerEvent(event: String): Boolean {
        val offered = eventQueue.offer(event)
        if (!offered) {
            droppedEventCount.incrementAndGet()
            eventQueue.poll()
            eventQueue.offer(event)
        }
        return true
    }

    fun recordEvent(eventId: Long, event: String) {
        eventHistory[eventId] = event
        while (eventHistory.size > SseConstants.EVENT_HISTORY_SIZE) {
            eventHistory.remove(eventHistory.firstKey())
        }
    }

    fun getEventsAfter(lastEventId: Long?): List<Pair<Long, String>> {
        if (lastEventId == null) {
            return emptyList()
        }
        return eventHistory.tailMap(lastEventId, false).entries.map { it.key to it.value }
    }

    fun updateActivity() {
        lastActivity = System.currentTimeMillis()
    }

    fun isExpired(timeoutMs: Long): Boolean {
        return System.currentTimeMillis() - lastActivity > timeoutMs
    }

    fun queueSize(): Int = eventQueue.size

    fun droppedCount(): Long = droppedEventCount.get()

    override fun toString(): String {
        return "SseSessionState(id=$id, projectPath=$projectPath, " +
            "initialized=$initialized, streamAttached=${streamAttached.get()}, " +
            "queueSize=${eventQueue.size}, droppedEvents=${droppedEventCount.get()}, " +
            "lastActivity=$lastActivity)"
    }
}
