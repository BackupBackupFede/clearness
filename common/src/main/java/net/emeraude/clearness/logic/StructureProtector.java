package net.emeraude.clearness.logic;

import net.emeraude.clearness.compat.Compat;
import net.emeraude.clearness.ClearnessConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Determines whether a position belongs to a generated structure (village,
 * stronghold, mineshaft, ancient city, bastion, etc.) and must therefore be
 * excluded from the Clearness.
 *
 * We do NOT protect the whole chunk: that would be far too much. We only
 * protect the actual volume of the structure's PIECES, each with its own
 * bounding box, much tighter than the chunk. We expand these boxes by a few
 * blocks:
 *  - downward (STRUCTURE_TRAIL_BELOW) to keep a small base of natural terrain
 *    under the structure — so it doesn't float on glass;
 *  - by a side/top cushion so the conversion doesn't nibble the edges of the
 *    walls and roof.
 *
 * The result is cached per chunk. The per-block test is then a few integer
 * comparisons.
 */
public final class StructureProtector {

    private static final List<BoundingBox> NONE = List.of();

    // cache per dimension: chunk (long pos) -> protected boxes touching it
    private final Map<String, Map<Long, List<BoundingBox>>> cache = new HashMap<>();

    /**
     * @return true if the position is inside a structure piece (expanded by
     *         its margins) and must therefore NOT be infected.
     */
    public boolean isProtected(ServerLevel level, BlockPos pos) {
        Map<Long, List<BoundingBox>> levelCache = cache.computeIfAbsent(
                Compat.dimensionId(level), k -> new HashMap<>());

        long chunkKey = Compat.chunkKey(pos.getX() >> 4, pos.getZ() >> 4);

        List<BoundingBox> boxes = levelCache.get(chunkKey);
        if (boxes == null) {
            boxes = computeChunkBoxes(level, Compat.chunkOf(pos));
            levelCache.put(chunkKey, boxes);
        }

        for (BoundingBox box : boxes) {
            if (box.isInside(pos)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Builds the list of (already-expanded) bounding boxes of the structure
     * pieces touching this chunk. A single piece can be registered in several
     * neighbouring chunks, which is intended.
     */
    private List<BoundingBox> computeChunkBoxes(ServerLevel level, ChunkPos chunkPos) {
        // What StructureManager.startsForStructure(chunk, any) does, written with the calls that
        // kept their shape: that method itself takes a ChunkPos up to 26.2 and two ints in 26.3.
        StructureManager structureManager = level.structureManager();
        List<StructureStart> starts = new ArrayList<>();
        ChunkAccess chunk = level.getChunk(chunkPos.getMinBlockX() >> 4, chunkPos.getMinBlockZ() >> 4, ChunkStatus.STRUCTURE_REFERENCES);
        for (Map.Entry<Structure, LongSet> entry : chunk.getAllReferences().entrySet()) {
            structureManager.fillStartsForStructure(entry.getKey(), entry.getValue(), starts::add);
        }
        if (starts.isEmpty()) {
            return NONE;
        }

        List<BoundingBox> boxes = new ArrayList<>();
        for (StructureStart start : starts) {
            if (!start.isValid()) {
                continue;
            }
            for (StructurePiece piece : start.getPieces()) {
                BoundingBox b = piece.getBoundingBox();
                if (!b.intersects(chunkPos.getMinBlockX(), chunkPos.getMinBlockZ(),
                        chunkPos.getMaxBlockX(), chunkPos.getMaxBlockZ())) {
                    continue;
                }
                boxes.add(expand(b));
            }
        }
        return boxes.isEmpty() ? NONE : boxes;
    }

    /** Expands a piece box: side/top cushion + downward trail. */
    private static BoundingBox expand(BoundingBox b) {
        return new BoundingBox(
                b.minX() - ClearnessConfig.STRUCTURE_MARGIN_SIDES,
                b.minY() - ClearnessConfig.STRUCTURE_TRAIL_BELOW,
                b.minZ() - ClearnessConfig.STRUCTURE_MARGIN_SIDES,
                b.maxX() + ClearnessConfig.STRUCTURE_MARGIN_SIDES,
                b.maxY() + ClearnessConfig.STRUCTURE_MARGIN_ABOVE,
                b.maxZ() + ClearnessConfig.STRUCTURE_MARGIN_SIDES);
    }
}
