package com.surfacemap;

/**
 * Canonical SHA-256 hash of the required XaeroWorldMap.properties file.
 *
 * HOW TO UPDATE THIS HASH:
 * 1. Edit XaeroWorldMap.properties to your desired surface-only settings (see below).
 * 2. Run:  sha256sum XaeroWorldMap.properties   (Linux/Mac)
 *          Get-FileHash XaeroWorldMap.properties -Algorithm SHA256  (Windows PowerShell)
 * 3. Paste the lowercase hex result as REQUIRED_HASH below.
 * 4. Recompile both server and client mods.
 * 5. Distribute the new client jar to your players.
 *
 * REQUIRED XaeroWorldMap.properties SETTINGS (surface-only, no extra info):
 * ------------------------------------------------------------------
 * renderCaves=false
 * caveMapsEnabled=false
 * showMobs=false
 * showPlayers=false
 * showWaypoints=false
 * showStructures=false
 * showNetherFortresses=false
 * showSlimeChunks=false
 * showBiomeOverlay=false
 * minimapEnabled=false
 * ------------------------------------------------------------------
 *
 * PLACEHOLDER — replace with your actual computed hash before building!
 */
public class ConfigHashReference {

    // TODO: Replace with the real SHA-256 of your XaeroWorldMap.properties
    public static final String REQUIRED_HASH =
            "46fb8a121c02d6744477d47a6aa189890db31708938af67cb6914b9e429e6cb5";
}
