package org.diggio.obdiggio.core.obd

abstract class Transport {
    private val buffer = ArrayDeque<Byte>()
    private val lock = Any()

    abstract fun open()
    abstract fun close()
    abstract fun write(data: ByteArray)
    abstract val isConnected: Boolean

    protected fun feed(data: ByteArray) {
        synchronized(lock) {
            data.forEach { buffer.addLast(it) }
        }
    }

    fun clear() {
        synchronized(lock) { buffer.clear() }
    }

    fun readUntil(terminator: Byte = '>', timeoutMs: Long = 5000): ByteArray {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            synchronized(lock) {
                val idx = buffer.indexOf(terminator)
                if (idx != -1) {
                    val result = ByteArray(idx + 1) { buffer.removeFirst() }
                    return result
                }
            }
            Thread.sleep(10)
        }
        return synchronized(lock) {
            buffer.toByteArray().also { buffer.clear() }
        }
    }
}
