package com.dogetennant.dworldmanager.server;

import org.bukkit.Material;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.dogetennant.dworldmanager.server.TestServer.messages;
import static org.assertj.core.api.Assertions.assertThat;

/** The plugin as the server starts it, and its config.yml as an admin edits it. */
class PluginServerTest {

    private TestServer mc;

    @BeforeEach
    void setUp() {
        mc = TestServer.start();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void startsOnSqliteWithTheBundledConfig() {
        assertThat(mc.plugin.isEnabled()).isTrue();
        assertThat(mc.plugin.getDataFolder().toPath().resolve("config.yml")).exists();
        assertThat(mc.plugin.getDataFolder().toPath().resolve("data.db")).exists();
        assertThat(mc.plugin.getConfigManager().getRestrictedMaterials()).containsExactlyInAnyOrder(
                Material.NETHERITE_BLOCK, Material.DIAMOND_BLOCK, Material.EMERALD_BLOCK, Material.GOLD_BLOCK,
                Material.BEACON);
    }

    @Test
    void anAdminsEditIsReadByReloadAndKeptThroughARestart() throws IOException {
        Path config = mc.plugin.getDataFolder().toPath().resolve("config.yml");
        String edited = Files.readString(config).replace("    - BEACON", "    - BEACON\n    - COPPER_BLOCK");
        Files.writeString(config, edited);
        PlayerMock admin = mc.admin();

        mc.run(admin, "dwm reload");
        mc.block(1, 10, 1, Material.COPPER_BLOCK);
        mc.run(admin, "dwm freeze region build 1 10 1 1 10 1");

        assertThat(messages(admin)).containsExactly(
                "dWorldManager config reloaded.",
                "Freeze region complete: 1 block(s) grandfathered in 'build'.");
        assertThat(Files.readString(config)).isEqualTo(edited);
        mc.restart();
        assertThat(Files.readString(config)).isEqualTo(edited);
        assertThat(mc.plugin.getConfigManager().getRestrictedMaterials()).contains(Material.COPPER_BLOCK);
    }
}
