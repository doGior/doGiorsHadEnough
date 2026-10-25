package it.dogior.hadEnough.util

import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

internal object Reflect {
    private val missing = Any()
    private val cache = ConcurrentHashMap<Pair<Class<*>, String>, Any>()

    fun field(type: Class<*>, name: String): Field? {
        val found = cache.getOrPut(type to name) { lookup(type, name) ?: missing }
        return found as? Field
    }

    fun get(target: Any, name: String): Any? = field(target.javaClass, name)?.let { runCatching { it.get(target) }.getOrNull() }

    fun set(target: Any, name: String, value: Any?): Boolean {
        val field = field(target.javaClass, name) ?: return false
        return runCatching { field.set(target, value) }.isSuccess
    }

    fun instanceFields(type: Class<*>): List<Field> = buildList {
        var current: Class<*>? = type
        while (current != null && current != Any::class.java) {
            current.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.forEach { field ->
                if (runCatching { field.isAccessible = true }.isSuccess) add(field)
            }
            current = current.superclass
        }
    }

    private fun lookup(type: Class<*>, name: String): Field? {
        var current: Class<*>? = type
        while (current != null) {
            runCatching { current.getDeclaredField(name) }.getOrNull()?.let { field ->
                return runCatching { field.apply { isAccessible = true } }.getOrNull()
            }
            current = current.superclass
        }
        return null
    }
}
