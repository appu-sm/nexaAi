package com.nexaai.assistant.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nexaai.assistant.domain.AllowedApp
import java.io.IOException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

data class AllowlistSnapshot(val apps: List<AllowedApp> = emptyList(), val warning: String? = null)

object AppAllowlistCodec {
    fun validate(apps: List<AllowedApp>) {
        require(apps.size <= 50 && apps.map { it.packageName }.distinct().size == apps.size) { "Allowlist must contain at most 50 unique apps" }
        val aliases = mutableSetOf<String>()
        apps.forEach { app ->
            require(app.packageName.length <= 200 && app.packageName.matches(Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+"))) { "Invalid installed package identifier" }
            require(app.aliases.size in 1..12) { "Each app needs 1-12 friendly names" }
            app.aliases.forEach { alias ->
                require(alias == normalizeAlias(alias) && alias.length in 1..60 && alias.matches(Regex("[\\p{L}\\p{N} '-]+"))) { "App names must be 1-60 letters, numbers, spaces, apostrophes or hyphens" }
                require(aliases.add(alias)) { "App alias collision: $alias" }
            }
        }
    }

    fun encode(apps: List<AllowedApp>): String {
        validate(apps)
        return buildJsonObject {
            put("schemaVersion", 1)
            put("apps", buildJsonArray { apps.forEach { app -> add(buildJsonObject {
                put("packageName", app.packageName)
                put("aliases", JsonArray(app.aliases.map(::JsonPrimitive)))
            }) } })
        }.toString()
    }

    fun decode(text: String): List<AllowedApp> {
        require(text.length <= 65536) { "Allowlist exceeds size limit" }
        val root = Json.parseToJsonElement(text).jsonObject
        require(root.keys == setOf("schemaVersion", "apps") && root.getValue("schemaVersion").jsonPrimitive.int == 1) { "Unsupported allowlist schema" }
        return root.getValue("apps").jsonArray.map { element ->
            val app = element.jsonObject
            require(app.keys == setOf("packageName", "aliases")) { "Invalid allowlist fields" }
            fun string(value: JsonElement): String { require(value.jsonPrimitive.isString); return value.jsonPrimitive.content }
            AllowedApp(string(app.getValue("packageName")), app.getValue("aliases").jsonArray.map(::string))
        }.also(::validate)
    }
}

class AppAllowlistRepository(private val store: DataStore<Preferences>) {
    private val key = stringPreferencesKey("app_allowlist_v1")
    val snapshots = store.data.map { preferences ->
        try {
            AllowlistSnapshot(preferences[key]?.let(AppAllowlistCodec::decode) ?: emptyList())
        } catch (invalid: IllegalArgumentException) {
            AllowlistSnapshot(warning = "App allowlist is invalid; no apps may launch. Reset the allowlist to recover.")
        } catch (invalid: SerializationException) {
            AllowlistSnapshot(warning = "App allowlist could not be decoded; no apps may launch. Reset the allowlist to recover.")
        }
    }.catch { error ->
        if (error is IOException) emit(AllowlistSnapshot(warning = "App allowlist storage is unavailable")) else throw error
    }

    suspend fun apps() = snapshots.first().apps
    suspend fun save(app: AllowedApp) {
        store.edit { preferences ->
            val existing = preferences[key]?.let(AppAllowlistCodec::decode) ?: emptyList()
            preferences[key] = AppAllowlistCodec.encode(existing.filterNot { it.packageName == app.packageName } + app)
        }
    }
    suspend fun remove(packageName: String) {
        store.edit { preferences ->
            preferences[key] = AppAllowlistCodec.encode((preferences[key]?.let(AppAllowlistCodec::decode) ?: emptyList()).filterNot { it.packageName == packageName })
        }
    }
    suspend fun reset() { store.edit { it.remove(key) } }
}