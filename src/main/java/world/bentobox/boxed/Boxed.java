package world.bentobox.boxed;

import java.util.Collections;

import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.World.Environment;
import org.bukkit.WorldCreator;
import org.bukkit.entity.SpawnCategory;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.eclipse.jdt.annotation.Nullable;

import world.bentobox.bentobox.api.addons.GameModeAddon;
import world.bentobox.bentobox.api.commands.admin.DefaultAdminCommand;
import world.bentobox.bentobox.api.commands.island.DefaultPlayerCommand;
import world.bentobox.bentobox.api.configuration.Config;
import world.bentobox.bentobox.api.configuration.WorldSettings;
import world.bentobox.bentobox.api.flags.Flag;
import world.bentobox.bentobox.api.flags.Flag.Mode;
import world.bentobox.bentobox.api.flags.Flag.Type;
import world.bentobox.bentobox.managers.RanksManager;
import world.bentobox.boxed.generators.BoxedBiomeGenerator;
import world.bentobox.boxed.generators.BoxedChunkGenerator;
import world.bentobox.boxed.generators.BoxedSeedChunkGenerator;
import world.bentobox.boxed.listeners.AdvancementListener;
import world.bentobox.boxed.listeners.EnderPearlListener;

/**
 * Main Boxed class - provides an survival game inside a box
 * @author tastybento
 */
public class Boxed extends GameModeAddon {

    public static final Flag MOVE_BOX = new Flag.Builder("MOVE_BOX", Material.COMPOSTER)
            .mode(Mode.BASIC)
            .type(Type.PROTECTION)
            .defaultRank(RanksManager.OWNER_RANK)
            .build();
    public static final Flag ALLOW_MOVE_BOX = new Flag.Builder("ALLOW_MOVE_BOX", Material.COMPOSTER)
            .mode(Mode.BASIC)
            .type(Type.WORLD_SETTING)
            .defaultSetting(true)
            .build();

    private static final String NETHER = "_nether";
    private static final String THE_END = "_the_end";

    // Settings
    private Settings settings;
    private BoxedChunkGenerator chunkGenerator;
    private final Config<Settings> configObject = new Config<>(this, Settings.class);
    private AdvancementsManager advManager;
    private ChunkGenerator netherChunkGenerator;
    private World seedWorld;
    private BiomeProvider boxedBiomeProvider;

    @Override
    public void onLoad() {
        // Save the default config from config.yml
        saveDefaultConfig();
        // Load settings from config.yml. This will check if there are any issues with it too.
        loadSettings();

        // Register commands
        playerCommand = new DefaultPlayerCommand(this) {};

        adminCommand = new DefaultAdminCommand(this) {};

    }

    private boolean loadSettings() {
        // Load settings again to get worlds
        settings = configObject.loadConfigObject();
        if (settings == null) {
            // Disable
            logError("Boxed settings could not load! Addon disabled.");
            setState(State.DISABLED);
            return false;
        }
        // Initialize the Generator because createWorlds will be run after onLoad
        this.chunkGenerator = new BoxedChunkGenerator(this);
        return true;
    }

    @Override
    public void onEnable() {
        // Check for recommended addons
        if (this.getPlugin().getAddonsManager().getAddonByName("Border").isEmpty()) {
            this.logWarning("Boxed normally requires the Border addon.");
        }
        if (this.getPlugin().getAddonsManager().getAddonByName("InvSwitcher").isEmpty()) {
            this.logWarning("Boxed normally requires the InvSwitcher addon for per-world Advancements.");
        }
        // Advancements manager
        advManager = new AdvancementsManager(this);
        // Make flags only applicable to this game mode
        MOVE_BOX.setGameModes(Collections.singleton(this));
        ALLOW_MOVE_BOX.setGameModes(Collections.singleton(this));
        // Register protection flag with BentoBox
        getPlugin().getFlagsManager().registerFlag(this, ALLOW_MOVE_BOX);
        if (ALLOW_MOVE_BOX.isSetForWorld(getOverWorld())) {
            getPlugin().getFlagsManager().registerFlag(this, MOVE_BOX);
        } else {
            getPlugin().getFlagsManager().unregister(MOVE_BOX);
        }

        // Register listeners
        this.registerListener(new AdvancementListener(this));
        this.registerListener(new EnderPearlListener(this));
        //this.registerListener(new DebugListener(this));

        // Register placeholders
        PlaceholdersManager phManager  = new PlaceholdersManager(this);
        getPlugin().getPlaceholdersManager().registerPlaceholder(this,"visited_island_advancements", phManager::getCountByLocation);
        getPlugin().getPlaceholdersManager().registerPlaceholder(this,"island_advancements", phManager::getCount);

    }

    @Override
    public void onDisable() {
        // Save the advancements cache
        getAdvManager().save();
    }

    @Override
    public void onReload() {
        if (loadSettings()) {
            log("Reloaded Boxed settings");
        }
    }

    /**
     * @return the settings
     */
    public Settings getSettings() {
        return settings;
    }

    @Override
    public void createWorlds() {
        // Create seed world
        log("Creating Boxed Seed world ...");
        seedWorld = WorldCreator
                .name("seed")
                .generator(new BoxedSeedChunkGenerator())
                .environment(Environment.NORMAL)
                .generateStructures(false)
                .seed(getSettings().getSeed())
                .createWorld();
        saveChunks(seedWorld);


        String worldName = settings.getWorldName().toLowerCase();
        /*
        if (getServer().getWorld(worldName) == null) {
            log("Creating Boxed Seed world ...");
        }
        seedWorld = WorldCreator.name(worldName + "_bak").seed(settings.getSeed()).createWorld();
        seedWorld.setDifficulty(Difficulty.PEACEFUL); // No damage wanted in this world.
         */
        if (getServer().getWorld(worldName) == null) {
            log("Creating Boxed world ...");
        }

        // Create the world if it does not exist
        islandWorld = getWorld(worldName, World.Environment.NORMAL);
        /*
        // Make the nether if it does not exist
        if (settings.isNetherGenerate()) {
            if (getServer().getWorld(worldName + NETHER) == null) {
                log("Creating Boxed's Nether...");
            }
            netherWorld = settings.isNetherIslands() ? getWorld(worldName, World.Environment.NETHER) : getWorld(worldName, World.Environment.NETHER);
        }
        // Make the end if it does not exist
        if (settings.isEndGenerate()) {
            if (getServer().getWorld(worldName + THE_END) == null) {
                log("Creating Boxed's End World...");
            }
            endWorld = settings.isEndIslands() ? getWorld(worldName, World.Environment.THE_END) : getWorld(worldName, World.Environment.THE_END);
        }
         */
    }

    private void saveChunks(World seedWorld) {
        int size = this.getSettings().getIslandDistance();
        double percent = size * 4 * size;
        int count = 0;
        for (int x = -size; x < size; x ++) {
            for (int z = -size; z < size; z++) {
                ChunkSnapshot chunk = seedWorld.getChunkAt(x, z).getChunkSnapshot(true, true, false);
                this.chunkGenerator.setChunk(chunk);
                count++;
                int p = (int) (count / percent * 100);
                if (p % 10 == 0) {
                    this.log("Storing seed chunks. " + p + "% done");
                }

            }
        }
    }

    /**
     * @return the chunkGenerator
     */
    public BoxedChunkGenerator getChunkGenerator() {
        return chunkGenerator;
    }

    /**
     * Gets a world or generates a new world if it does not exist
     * @param worldName2 - the overworld name
     * @param env - the environment
     * @return world loaded or generated
     */
    private World getWorld(String worldName2, Environment env) {
        // Set world name
        worldName2 = env.equals(World.Environment.NETHER) ? worldName2 + NETHER : worldName2;
        worldName2 = env.equals(World.Environment.THE_END) ? worldName2 + THE_END : worldName2;
        boxedBiomeProvider = new BoxedBiomeGenerator(this);
        World w = WorldCreator
                .name(worldName2)
                .generator(chunkGenerator)
                .environment(env)
                .seed(seedWorld.getSeed()) // For development
                .createWorld();
        // Set spawn rates
        if (w != null) {
            setSpawnRates(w);
        }
        return w;

    }

    /**
     * @return the boxedBiomeProvider
     */
    public BiomeProvider getBoxedBiomeProvider() {
        return boxedBiomeProvider;
    }

    private void setSpawnRates(World w) {
        if (getSettings().getSpawnLimitMonsters() > 0) {
            w.setSpawnLimit(SpawnCategory.MONSTER, getSettings().getSpawnLimitMonsters());
        }
        if (getSettings().getSpawnLimitAmbient() > 0) {
            w.setSpawnLimit(SpawnCategory.AMBIENT, getSettings().getSpawnLimitAmbient());
        }
        if (getSettings().getSpawnLimitAnimals() > 0) {
            w.setSpawnLimit(SpawnCategory.ANIMAL, getSettings().getSpawnLimitAnimals());
        }
        if (getSettings().getSpawnLimitWaterAnimals() > 0) {
            w.setSpawnLimit(SpawnCategory.WATER_ANIMAL, getSettings().getSpawnLimitWaterAnimals());
        }
        if (getSettings().getTicksPerAnimalSpawns() > 0) {
            w.setTicksPerSpawns(SpawnCategory.ANIMAL, getSettings().getTicksPerAnimalSpawns());
        }
        if (getSettings().getTicksPerMonsterSpawns() > 0) {
            w.setTicksPerSpawns(SpawnCategory.MONSTER, getSettings().getTicksPerMonsterSpawns());
        }
    }

    @Override
    public WorldSettings getWorldSettings() {
        return getSettings();
    }

    @Override
    public @Nullable ChunkGenerator getDefaultWorldGenerator(String worldName, String id) {
        return worldName.endsWith(NETHER) ? netherChunkGenerator : chunkGenerator;
    }

    @Override
    public void saveWorldSettings() {
        if (settings != null) {
            configObject.saveConfigObject(settings);
        }
    }

    /* (non-Javadoc)
     * @see world.bentobox.bentobox.api.addons.Addon#allLoaded()
     */
    @Override
    public void allLoaded() {
        // Save settings. This will occur after all addons have loaded
        this.saveWorldSettings();
    }

    /**
     * @return the advManager
     */
    public AdvancementsManager getAdvManager() {
        return advManager;
    }

}
