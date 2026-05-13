package com.surfacemap;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Properties;

@Environment(EnvType.CLIENT)
public class SurfaceMapClient implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("surfacemap-client");

    // Must match server-side identifiers exactly
    public static final Identifier CONFIG_REQUEST_PACKET  = new Identifier("surfacemap", "config_request");
    public static final Identifier CONFIG_RESPONSE_PACKET = new Identifier("surfacemap", "config_response");
    public static final Identifier CONFIG_ENFORCE_PACKET  = new Identifier("surfacemap", "config_enforce");

    // Path to Xaero's config file inside .minecraft
    private static final String XAERO_CONFIG_PATH = "config/xaeroworldmap.properties";

    // Whether config is currently locked (server enforcing)
    private static boolean configLocked = false;

    @Override
    public void onInitializeClient() {
        LOGGER.info("[SurfaceMap] Client mod initialising…");

        // When server sends a config-request, compute hash and respond
        ClientPlayNetworking.registerGlobalReceiver(CONFIG_REQUEST_PACKET, (client, handler, buf, responseSender) -> {
            String expectedHash = buf.readString(256);
            LOGGER.info("[SurfaceMap] Server requested config verification (expects: {}…)", expectedHash.substring(0, 8));

            client.execute(() -> {
                // 1. Enforce the correct settings on disk
                enforceConfig();

                // 2. Compute hash of the file
                String hash = computeConfigHash();
                LOGGER.info("[SurfaceMap] Sending config hash: {}…", hash.substring(0, 8));

                // 3. Send response to server
                PacketByteBuf responseBuf = PacketByteBufs.create();
                responseBuf.writeString(hash);
                ClientPlayNetworking.send(CONFIG_RESPONSE_PACKET, responseBuf);
            });
        });

        // When server confirms verification, lock the config
        ClientPlayNetworking.registerGlobalReceiver(CONFIG_ENFORCE_PACKET, (client, handler, buf, responseSender) -> {
            boolean enforce = buf.readBoolean();
            configLocked = enforce;
            if (enforce) {
                LOGGER.info("[SurfaceMap] Config locked by server — Xaero's settings are read-only.");
            }
        });

        // On disconnect, unlock config so player can change settings in singleplayer
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            configLocked = false;
            LOGGER.info("[SurfaceMap] Disconnected — config lock released.");
        });

        // Periodic config watchdog: re-enforce if player modified the file while locked
        startConfigWatchdog();

        LOGGER.info("[SurfaceMap] Client mod ready.");
    }

    /**
     * Writes the mandatory surface-only settings to XaeroWorldMap config file.
     * Preserves other settings (zoom level, colours etc.) that don't affect info advantage.
     */
    public static void enforceConfig() {
        Path configPath = getConfigPath();
        Properties props = new Properties();

        // Load existing config if present (preserve innocent settings)
        if (Files.exists(configPath)) {
            try (FileInputStream fis = new FileInputStream(configPath.toFile())) {
                props.load(fis);
            } catch (IOException e) {
                LOGGER.warn("[SurfaceMap] Could not read existing config: {}", e.getMessage());
            }
        }

        // === ENFORCE SURFACE-ONLY SETTINGS ===
        // Caves & underground
        props.setProperty("renderCaves",           "false");
        props.setProperty("caveMapsEnabled",        "false");
        props.setProperty("renderUnderground",      "false");

        // Entities
        props.setProperty("showMobs",              "false");
        props.setProperty("showPlayers",           "false");
        props.setProperty("showAnimals",           "false");
        props.setProperty("showVillagers",         "false");
        props.setProperty("showHostileMobs",       "false");

        // Points of interest / structures
        props.setProperty("showWaypoints",         "false");
        props.setProperty("showStructures",        "false");
        props.setProperty("showNetherFortresses",  "false");
        props.setProperty("showSlimeChunks",       "false");
        props.setProperty("showBiomeOverlay",      "false");
        props.setProperty("showGridLines",         "false");
        props.setProperty("showSpawnChunks",       "false");

        // Minimap — disable; server may allow/disallow separately
        props.setProperty("minimapEnabled",        "false");

        // Write back
        try {
            Files.createDirectories(configPath.getParent());
            try (FileOutputStream fos = new FileOutputStream(configPath.toFile())) {
                props.store(fos, "SurfaceMap enforced config — DO NOT EDIT while on server");
            }
            LOGGER.info("[SurfaceMap] Config enforced at {}", configPath);
        } catch (IOException e) {
            LOGGER.error("[SurfaceMap] Failed to write config: {}", e.getMessage());
        }
    }

    /**
     * Computes SHA-256 of the Xaero config file and returns lowercase hex string.
     */
    public static String computeConfigHash() {
        Path configPath = getConfigPath();
        if (!Files.exists(configPath)) {
            return "FILE_NOT_FOUND";
        }
        try {
            byte[] fileBytes = Files.readAllBytes(configPath);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(fileBytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (IOException | NoSuchAlgorithmException e) {
            LOGGER.error("[SurfaceMap] Hash computation failed: {}", e.getMessage());
            return "HASH_ERROR";
        }
    }

    /**
     * Background watchdog: if config is locked and the file changes, re-enforce immediately.
     */
    private void startConfigWatchdog() {
        Thread watchdog = new Thread(() -> {
            String lastHash = "";
            while (true) {
                try {
                    Thread.sleep(3000); // check every 3 seconds
                } catch (InterruptedException ignored) {}

                if (!configLocked) continue;

                String currentHash = computeConfigHash();
                if (!currentHash.equals(lastHash) && !lastHash.isEmpty()) {
                    LOGGER.warn("[SurfaceMap] Config tampered! Re-enforcing…");
                    enforceConfig();
                }
                lastHash = computeConfigHash(); // re-read after potential enforce
            }
        }, "surfacemap-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private static Path getConfigPath() {
        // net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir() equivalent via system property
        String gameDir = System.getProperty("user.dir");
        // When running inside Minecraft launcher, working dir is .minecraft
        return Paths.get(gameDir, XAERO_CONFIG_PATH);
    }

    public static boolean isConfigLocked() {
        return configLocked;
    }
}
