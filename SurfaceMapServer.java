package com.surfacemap;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerLoginConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerLoginNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class SurfaceMapServer implements ModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("surfacemap-server");

    // Network channel identifiers
    public static final Identifier CONFIG_REQUEST_PACKET  = new Identifier("surfacemap", "config_request");
    public static final Identifier CONFIG_RESPONSE_PACKET = new Identifier("surfacemap", "config_response");
    public static final Identifier CONFIG_ENFORCE_PACKET  = new Identifier("surfacemap", "config_enforce");

    // The canonical SHA-256 hash of the required Xaero's config.
    // Generated from XaeroWorldMap.properties with surface-only settings.
    // Update this value by running: sha256sum XaeroWorldMap.properties
    public static final String REQUIRED_CONFIG_HASH = ConfigHashReference.REQUIRED_HASH;

    // Players who have passed config verification
    private static final Map<UUID, Boolean> verifiedPlayers = new HashMap<>();

    // How long (ms) to wait for config response before kicking
    private static final long VERIFICATION_TIMEOUT_MS = 10_000;

    private MinecraftServer server;

    @Override
    public void onInitialize() {
        LOGGER.info("[SurfaceMap] Server mod initialising…");

        // Store server reference on startup
        ServerLifecycleEvents.SERVER_STARTED.register(s -> this.server = s);

        // When a player finishes logging in, send them a config request
        ServerPlayConnectionEvents.JOIN.register((handler, sender, srv) -> {
            ServerPlayerEntity player = handler.getPlayer();
            LOGGER.info("[SurfaceMap] Requesting config from {}", player.getName().getString());

            // Mark as unverified
            verifiedPlayers.put(player.getUuid(), false);

            // Send config-request packet
            PacketByteBuf buf = PacketByteBufs.create();
            buf.writeString(REQUIRED_CONFIG_HASH); // tell client what hash we expect
            sender.sendPacket(CONFIG_REQUEST_PACKET, buf);

            // Schedule a timeout check
            new Thread(() -> {
                try {
                    Thread.sleep(VERIFICATION_TIMEOUT_MS);
                } catch (InterruptedException ignored) {}

                Boolean verified = verifiedPlayers.get(player.getUuid());
                if (verified == null || !verified) {
                    // Player never responded or sent wrong hash — kick on server thread
                    srv.execute(() -> {
                        if (player.networkHandler != null) {
                            player.networkHandler.disconnect(
                                Text.literal("§c[SurfaceMap] Config verification failed.\n" +
                                        "§eYou must have SurfaceMap client mod installed\n" +
                                        "§eand Xaero's World Map with surface-only settings.")
                            );
                            LOGGER.warn("[SurfaceMap] Kicked {} — config not verified in time.",
                                    player.getName().getString());
                        }
                    });
                }
            }, "surfacemap-timeout-" + player.getName().getString()).start();
        });

        // Handle config response from client
        ServerPlayNetworking.registerGlobalReceiver(CONFIG_RESPONSE_PACKET, (srv, player, handler, buf, responseSender) -> {
            String receivedHash = buf.readString(256);
            boolean valid = REQUIRED_CONFIG_HASH.equalsIgnoreCase(receivedHash.trim());

            verifiedPlayers.put(player.getUuid(), valid);

            if (valid) {
                LOGGER.info("[SurfaceMap] {} passed config check ✓", player.getName().getString());
                // Optionally send an "enforce" packet so client locks its settings UI
                PacketByteBuf enforceBuf = PacketByteBufs.create();
                enforceBuf.writeBoolean(true);
                responseSender.sendPacket(CONFIG_ENFORCE_PACKET, enforceBuf);
            } else {
                LOGGER.warn("[SurfaceMap] {} sent wrong config hash: {}", player.getName().getString(), receivedHash);
                player.networkHandler.disconnect(
                    Text.literal("§c[SurfaceMap] Wrong Xaero's config detected.\n" +
                            "§eOnly surface-only settings are allowed on this server.\n" +
                            "§7Expected: " + REQUIRED_CONFIG_HASH.substring(0, 8) + "…\n" +
                            "§7Got:      " + receivedHash.substring(0, Math.min(8, receivedHash.length())) + "…")
                );
            }
        });

        // Clean up when player disconnects
        ServerPlayConnectionEvents.DISCONNECT.register((handler, srv) ->
            verifiedPlayers.remove(handler.getPlayer().getUuid())
        );

        LOGGER.info("[SurfaceMap] Server mod ready. Required config hash: {}…", REQUIRED_CONFIG_HASH.substring(0, 12));
    }

    public static boolean isPlayerVerified(UUID uuid) {
        return verifiedPlayers.getOrDefault(uuid, false);
    }
}
