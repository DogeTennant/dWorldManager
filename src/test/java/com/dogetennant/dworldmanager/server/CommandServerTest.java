package com.dogetennant.dworldmanager.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static com.dogetennant.dworldmanager.server.TestServer.messages;
import static org.assertj.core.api.Assertions.assertThat;

/** {@code /dwm} as players with real permissions type it: help, tab completion, wrong input. */
class CommandServerTest {

    private TestServer mc;

    @BeforeEach
    void setUp() {
        mc = TestServer.start();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private PlayerMock auditor() {
        PlayerMock auditor = mc.player("Auditor");
        auditor.addAttachment(mc.plugin, "dworldmanager.audit", true);
        return auditor;
    }

    @Test
    void helpShowsOnlyWhatThePlayerMayUse() {
        PlayerMock auditor = auditor();

        mc.run(auditor, "dwm");

        assertThat(messages(auditor)).containsExactly(
                "dWorldManager " + mc.plugin.getPluginMeta().getVersion(),
                "/dwm frozen <world> [material] - Show frozen block counts, or coordinates for one material",
                "/dwm auditlog [limit] - Show recent staff unfreeze actions");
    }

    @Test
    void aPlayerWithoutAnyDWorldManagerPermissionCannotRunIt() {
        PlayerMock steve = mc.player("Steve");

        mc.run(steve, "dwm");
        mc.run(steve, "dwm frozen build");

        // the server refuses it before dWorldManager sees it: /dwm needs one of its permissions
        assertThat(messages(steve)).hasSize(2).allSatisfy(message ->
                assertThat(message).startsWith("I'm sorry, but you do not have permission to perform this command."));
    }

    @Test
    void aSubcommandWithoutItsPermissionIsRefused() {
        PlayerMock auditor = auditor();

        mc.run(auditor, "dwm unfreeze build");

        assertThat(messages(auditor)).containsExactly("You don't have permission to do that.");
    }

    @Test
    void tabCompletionOffersWhatThePlayerMayUseAndTheServersWorlds() {
        PlayerMock admin = mc.admin();

        assertThat(mc.server.getCommandTabComplete(auditor(), "dwm ")).containsExactly("frozen", "auditlog");
        assertThat(mc.server.getCommandTabComplete(admin, "dwm fr")).containsExactly("freeze", "frozen");
        assertThat(mc.server.getCommandTabComplete(admin, "dwm freeze ")).containsExactly("build", "region", "block");
        assertThat(mc.server.getCommandTabComplete(admin, "dwm freeze build --pl")).containsExactly("--placed-only");
        assertThat(mc.server.getCommandTabComplete(admin, "dwm frozen build diamond_bl")).containsExactly("DIAMOND_BLOCK");
        assertThat(mc.server.getCommandTabComplete(admin, "dwm clearcontainers b")).containsExactly("build");
    }

    @Test
    void wrongInputIsExplained() {
        PlayerMock admin = mc.admin();

        mc.run(admin, "dwm melt");
        mc.run(admin, "dwm freeze nowhere");
        mc.run(admin, "dwm freeze build NOT_A_BLOCK");
        mc.run(admin, "dwm freeze region build 0 0 0 1 one 1");
        mc.run(admin, "dwm auditlog lots");

        assertThat(messages(admin)).containsExactly(
                "Unknown subcommand 'melt'. Run /dwm for a list.",
                "Unknown world: nowhere",
                "Unknown material: NOT_A_BLOCK",
                "Coordinates must be whole numbers.",
                "Limit must be a whole number.");
    }

    @Test
    void theConsoleCannotFreezeTheBlockItLooksAt() {
        mc.run(mc.console(), "dwm freeze block");

        assertThat(messages(mc.console())).containsExactly("This command can only be run by a player.");
    }
}
