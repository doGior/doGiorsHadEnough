package it.dogior.hadEnough.subtitles

import java.lang.reflect.Proxy

internal object ListenerProxy {
    fun <T> create(type: Class<T>, handlers: Map<String, (Array<out Any?>) -> Unit>): T {
        val proxy = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { self, method, args ->
            val arguments = args ?: emptyArray()
            when (method.name) {
                "equals" -> self === arguments.firstOrNull()
                "hashCode" -> System.identityHashCode(self)
                "toString" -> "StreamCenter${type.simpleName}"
                else -> {
                    handlers[method.name]?.let { handler ->
                        runCatching { handler(arguments) }.onFailure { AssLog.error("Evento del player non gestito", it) }
                    }
                    null
                }
            }
        }
        @Suppress("UNCHECKED_CAST")
        return proxy as T
    }
}
