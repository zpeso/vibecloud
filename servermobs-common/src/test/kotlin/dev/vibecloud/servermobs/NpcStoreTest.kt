package dev.vibecloud.servermobs

import dev.vibecloud.servermobs.model.NpcAction
import dev.vibecloud.servermobs.model.NpcActionType
import dev.vibecloud.servermobs.model.NpcData
import dev.vibecloud.servermobs.model.NpcSkin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Logger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NpcStoreTest {
    private lateinit var directory: Path
    private lateinit var store: NpcStore

    @BeforeEach
    fun setUp() {
        directory = Files.createTempDirectory("servermobs-store-test")
        store = NpcStore(directory, Logger.getLogger("servermobs-test"))
    }

    @AfterEach
    fun tearDown() {
        if (Files.exists(directory)) {
            Files.walk(directory).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    @Test
    fun `round trips an npc with skin, hologram and actions`() {
        val data = NpcData(
            name = "shopkeeper",
            group = "lobby",
            world = "world",
            x = 10.5,
            y = 64.0,
            z = -3.25,
            yaw = 90f,
            pitch = 12.5f,
            skin = NpcSkin(value = "dGV4dHVyZQ==", signature = "c2ln", source = "Notch"),
            hologram = listOf("<yellow>Shop", "<gray>Right-click me"),
            actions = listOf(
                NpcAction(NpcActionType.TRANSFER, "citybuild"),
                NpcAction(NpcActionType.MESSAGE, "<green>Welcome!"),
            ),
            showNametag = false,
            turnToPlayer = true,
        )

        store.save(data)

        val loaded = assertNotNull(store.find("shopkeeper"))
        assertEquals(data, loaded)
    }

    @Test
    fun `lookup is case-insensitive and filters by group`() {
        store.save(sample("LobbyNpc", "lobby"))
        store.save(sample("CityNpc", "citybuild"))

        assertEquals("LobbyNpc", store.find("lobbynpc")?.name)
        assertEquals(listOf("LobbyNpc"), store.findByGroup("LOBBY").map { it.name })

        assertEquals(2, store.list().size)
    }

    @Test
    fun `delete removes the definition`() {
        store.save(sample("temp", "lobby"))
        assertTrue(store.delete("temp"))
        assertFalse(store.exists("temp"))
        assertNull(store.find("temp"))
    }

    @Test
    fun `unparsable files are skipped instead of failing the load`() {
        Files.createDirectories(directory)
        Files.writeString(directory.resolve("broken.yml"), "name: broken\nx: [1, 2\n")
        store.save(sample("good", "lobby"))

        assertEquals(listOf("good"), store.list().map { it.name })
    }

    @Test
    fun `defaults the nametag and turn-to-player flags when absent`() {
        store.save(sample("plain", "lobby"))
        val loaded = assertNotNull(store.find("plain"))
        assertTrue(loaded.showNametag)
        assertFalse(loaded.turnToPlayer)
    }

    @Test
    fun `validates npc names`() {
        assertTrue(store.isValidName("lobby-shop_1"))
        assertFalse(store.isValidName("has space"))
        assertFalse(store.isValidName(""))
        assertFalse(store.isValidName("a".repeat(33)))
    }

    private fun sample(name: String, group: String): NpcData =
        NpcData(name, group, "world", 0.0, 0.0, 0.0, 0f, 0f)
}
