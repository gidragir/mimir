package dev.mimir.obsidian.fakes

import dev.mimir.obsidian.platform.LocalStorageFn

class FakeLocalStorage(
    private val data: MutableMap<String, String> = mutableMapOf(),
) : LocalStorageFn {
    override fun load(key: String): String? = data[key]

    override fun save(key: String, value: String?) {
        if (value == null) {
            data.remove(key)
        } else {
            data[key] = value
        }
    }

    fun containsKey(key: String): Boolean = data.containsKey(key)
}
