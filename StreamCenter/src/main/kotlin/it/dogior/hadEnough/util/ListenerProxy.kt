package it.dogior.hadEnough.util

import java.lang.reflect.Proxy

internal object ListenerProxy {
    fun <T> create(
        type: Class<T>,
        handlers: Map<String, (Array<out Any?>) -> Unit>,
        onError: (Throwable) -> Unit,
    ): T {
        val proxy = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { self, method, args ->
            val arguments = args ?: emptyArray()
            when (method.name) {
                "equals" -> self === arguments.firstOrNull()
                "hashCode" -> System.identityHashCode(self)
                "toString" -> "StreamCenter${type.simpleName}"
                else -> {
                    handlers[method.name]?.let { handler ->
                        runCatching { handler(arguments) }.onFailure(onError)
                    }
                    null
                }
            }
        }
        @Suppress("UNCHECKED_CAST")
        return proxy as T
    }
}
