package dev.vibecloud.servermobs

import dev.vibecloud.servermobs.model.NpcAction
import dev.vibecloud.servermobs.model.NpcActionType
import dev.vibecloud.servermobs.model.NpcData
import dev.vibecloud.servermobs.model.NpcSkin
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.logging.Logger
import java.util.stream.Collectors

/**
 * Persists NPC definitions as one YAML file per NPC (`<name>.yml`) inside [directory] — normally
 * `servermobs/` under the VibeCloud home directory so definitions survive non-static service
 * restarts. Reads are defensive: a corrupt or partially written file is logged and skipped rather
 * than failing a service start.
 *
 * Kept Java 8 compatible so the same code compiles into both the modern and the 1.8 build.
 */
class NpcStore(
    private val directory: Path,
    private val logger: Logger,
) {
    private val loader = Yaml(
        SafeConstructor(
            LoaderOptions().apply {
                isAllowDuplicateKeys = false
                codePointLimit = 1 * 1024 * 1024
            },
        ),
    )
    private val dumper = Yaml(
        DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            indent = 2
            indicatorIndent = 0
            isPrettyFlow = true
        },
    )

    @Synchronized
    fun ensureDirectory() {
        Files.createDirectories(directory)
    }

    fun isValidName(name: String): Boolean = NAME_PATTERN.matches(name)

    private fun file(name: String): Path = directory.resolve(name.lowercase() + YAML_SUFFIX)

    @Synchronized
    fun exists(name: String): Boolean = Files.isRegularFile(file(name))

    @Synchronized
    fun find(name: String): NpcData? {
        val target = file(name)
        if (!Files.isRegularFile(target)) return null
        return runCatching {
            Files.newBufferedReader(target).use { reader -> parse(loader.load<Any?>(reader), name) }
        }.onFailure {
            logger.warning("Could not read NPC '$name' from $target: ${it.message}")
        }.getOrNull()
    }

    @Synchronized
    fun list(): List<NpcData> {
        if (!Files.isDirectory(directory)) return emptyList()
        val names = Files.list(directory).use { stream ->
            stream.map { it.fileName.toString() }
                .filter { it.endsWith(YAML_SUFFIX) }
                .map { it.substring(0, it.length - YAML_SUFFIX.length) }
                .collect(Collectors.toList())
        }
        return names.mapNotNull { find(it) }
    }

    /** All NPCs belonging to [group] (case-insensitive). */
    @Synchronized
    fun findByGroup(group: String): List<NpcData> = list().filter { it.group.equals(group, ignoreCase = true) }

    @Synchronized
    fun save(data: NpcData) {
        ensureDirectory()
        val document = linkedMapOf<String, Any>(
            "name" to data.name,
            "group" to data.group,
            "world" to data.world,
            "x" to data.x,
            "y" to data.y,
            "z" to data.z,
            "yaw" to data.yaw,
            "pitch" to data.pitch,
            "show-nametag" to data.showNametag,
            "turn-to-player" to data.turnToPlayer,
        )
        data.skin?.let { skin ->
            document["skin"] = linkedMapOf<String, Any?>(
                "source" to skin.source,
                "value" to skin.value,
                "signature" to skin.signature,
            )
        }
        document["hologram"] = data.hologram
        document["actions"] = data.actions.map { action ->
            linkedMapOf<String, Any>("type" to action.type.id, "value" to action.value)
        }
        val target = file(data.name)
        val temporary = target.resolveSibling(target.fileName.toString() + ".tmp")
        Files.newBufferedWriter(temporary).use { writer -> dumper.dump(document, writer) }
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
    }

    @Synchronized
    fun delete(name: String): Boolean = Files.deleteIfExists(file(name))

    private fun parse(root: Any?, fallbackName: String): NpcData? {
        val map = root as? Map<*, *> ?: return null
        val name = (map["name"] as? String)?.trim().orEmpty().ifBlank { fallbackName }
        val group = (map["group"] as? String)?.trim().orEmpty()
        val world = (map["world"] as? String)?.trim().orEmpty().ifBlank { "world" }
        val x = (map["x"] as? Number)?.toDouble() ?: return null
        val y = (map["y"] as? Number)?.toDouble() ?: return null
        val z = (map["z"] as? Number)?.toDouble() ?: return null
        val yaw = (map["yaw"] as? Number)?.toFloat() ?: 0f
        val pitch = (map["pitch"] as? Number)?.toFloat() ?: 0f
        val skin = (map["skin"] as? Map<*, *>)?.let { skinMap ->
            val value = (skinMap["value"] as? String)?.trim().orEmpty()
            if (value.isEmpty()) {
                null
            } else {
                NpcSkin(
                    value = value,
                    signature = (skinMap["signature"] as? String)?.trim()?.takeIf { it.isNotEmpty() },
                    source = (skinMap["source"] as? String)?.trim().orEmpty(),
                )
            }
        }
        val hologram = (map["hologram"] as? List<*>).orEmpty().mapNotNull { it as? String }
        val actions = (map["actions"] as? List<*>).orEmpty().mapNotNull { raw ->
            val actionMap = raw as? Map<*, *> ?: return@mapNotNull null
            val type = NpcActionType.parse((actionMap["type"] as? String).orEmpty()) ?: return@mapNotNull null
            val value = (actionMap["value"] as? String).orEmpty()
            NpcAction(type, value)
        }
        val showNametag = (map["show-nametag"] as? Boolean) ?: true
        val turnToPlayer = (map["turn-to-player"] as? Boolean) ?: false
        return NpcData(
            name, group, world, x, y, z, yaw, pitch, skin, hologram, actions, showNametag, turnToPlayer,
        )
    }

    companion object {
        private const val YAML_SUFFIX = ".yml"
        private val NAME_PATTERN = Regex("[A-Za-z0-9_-]{1,32}")
    }
}
