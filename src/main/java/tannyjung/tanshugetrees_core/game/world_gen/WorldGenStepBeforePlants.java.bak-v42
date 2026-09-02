package tannyjung.tanshugetrees_core.game.world_gen;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import tannyjung.tanshugetrees_core.Core;
import tannyjung.tanshugetrees_core.game.GameUtils;
import tannyjung.tanshugetrees_handcode.systems.world_gen.WorldGen;

public class WorldGenStepBeforePlants extends Feature <NoneFeatureConfiguration> {

    public WorldGenStepBeforePlants() {

        super(NoneFeatureConfiguration.CODEC);
        // [诊断] 确认 Feature 是否被注册实例化
        if (Core.debug_log) System.out.println("[THT-DEBUG] WorldGenStepBeforePlants constructor called - Feature registered!");

    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {

        // [诊断] 确认 MC 是否调用了 place()
        if (Core.debug_log) System.out.println("[THT-DEBUG] WorldGenStepBeforePlants.place() called! Origin: " + context.origin());

        LevelAccessor level_accessor = context.level();
        ServerLevel level_server = context.level().getLevel();
        ChunkGenerator chunk_generator = context.chunkGenerator();
        String dimension = GameUtils.Space.getDimensionID(level_server).replace(":", "-");
        ChunkPos chunk_pos = new ChunkPos(context.origin());

        WorldGen.stepBeforePlants(level_accessor, level_server, chunk_generator, dimension, chunk_pos);
        return true;

    }

}