package net.pcal.footpaths;

import com.google.common.math.DoubleMath;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.registry.Registry;
import net.minecraft.world.World;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static java.util.Objects.requireNonNull;


/**
 * Central singleton service.
 */
public class FootpathsService {

    // ===================================================================================
    // Constants

    public static final String LOGGER_NAME = "footpaths";
    public static final String LOG_PREFIX = "[Footpaths] ";

    // ===================================================================================
    // Fields

    private Set<String> spawnGroups;
    private Set<Identifier> entityIds;

    // ===================================================================================
    // Singleton

    private static final class SingletonHolder {
        private static final FootpathsService INSTANCE;

        static {
            INSTANCE = new FootpathsService();
        }
    }

    public static FootpathsService getInstance() {
        return SingletonHolder.INSTANCE;
    }

    static final class BlockHistory {
        BlockHistory(int stepCount, long lastStepTimestamp) {
            this.stepCount = stepCount;
            this.lastStepTimestamp = lastStepTimestamp;
        }

        int stepCount;
        long lastStepTimestamp;

        @Override
        public String toString() {
            return "stepCount: " + this.stepCount + " lastStepTimestamp: " + this.lastStepTimestamp;
        }
    }

    // ===================================================================================
    // Constructors

    FootpathsService() {
        this.stepCounts = new HashMap<>();
    }

    public void initBlockConfig(FootpathsRuntimeConfig config) {
        this.config = requireNonNull(config);
        this.spawnGroups = new HashSet<>();
        this.entityIds = new HashSet<>();
        for(FootpathsRuntimeConfig.RuntimeBlockConfig rbc : config.getAllConfigs()) {
            this.spawnGroups.addAll(rbc.spawnGroups());
            this.entityIds.addAll(rbc.entityIds());
        }
        if (this.spawnGroups.isEmpty()) this.spawnGroups = null;
        if (this.entityIds.isEmpty()) this.entityIds = null;
    }

    // ===================================================================================
    // Fields

    private final Logger logger = LogManager.getLogger(LOGGER_NAME);
    private FootpathsRuntimeConfig config;
    private final Map<BlockPos, BlockHistory> stepCounts;

    public void entitySteppedOnBlock(Entity entity) {
        if (!DoubleMath.isMathematicalInteger(entity.getY())) {
            // Ignore block changes for entities that aren't standing on the ground.
            // Mainly because the jumping players register extra blockPos changes
            // that I don't quite understand.
            return;
        }
        if (!isMatchingEntity(entity, this.entityIds, this.spawnGroups)) {
            // this is presumably going to be the case the vast majority of the time, so try to detect and
            // short-circuit it as quickly as possible.
            return;
        }
        final BlockPos pos = entity.getBlockPos().down(1);
        final World world = entity.getWorld();
        final BlockState state = world.getBlockState(pos);
        final Block block = state.getBlock();
        final Identifier blockId = Registry.BLOCK.getId(block);
        logger.debug(() -> "checking " + blockId);
        if (this.config.hasBlockConfig(blockId)) {
            final FootpathsRuntimeConfig.RuntimeBlockConfig pc = this.config.getBlockConfig(blockId);
            final BlockHistory bh = this.stepCounts.get(pos);
            final int blockStepCount;
            if (!isMatchingEntity(entity, pc.entityIds(), pc.spawnGroups())) return;
            if (bh == null) {
                blockStepCount = 1;
            } else {
                if (pc.timeoutTicks() > 0 && (world.getTime() - bh.lastStepTimestamp) > pc.timeoutTicks()) {
                    logger.debug(() -> "step timeout " + block + " " + bh);
                    blockStepCount = 1;
                    bh.stepCount = 1;
                } else {
                    logger.debug(() -> "stepCount++ " + block + " " + bh);
                    bh.stepCount++;
                    blockStepCount = bh.stepCount;
                }
                bh.lastStepTimestamp = world.getTime();
            }
            if (blockStepCount >= pc.stepCount()) {
                logger.debug(() -> "changed! " + block + " " + bh);
                final Identifier nextId = pc.nextId();
                world.setBlockState(pos, Registry.BLOCK.get(nextId).getDefaultState());
                if (bh != null) this.stepCounts.remove(pos);
            } else {
                if (bh == null) {
                    this.stepCounts.put(pos, new BlockHistory(blockStepCount, world.getTime()));
                }
            }
        }
    }


    private static boolean isMatchingEntity(Entity entity, Set<Identifier> entityIds, Set<String> spawnGroups) {
        if (entityIds != null) {
            final Identifier entityId = Registry.ENTITY_TYPE.getId(entity.getType());
            if (entityIds.contains(entityId)) return true;
        }
        if (spawnGroups != null) {
            final SpawnGroup group = entity.getType().getSpawnGroup();
            if (group != null && spawnGroups.contains(group.getName())) return true;
        }
        return false;
    }

}
