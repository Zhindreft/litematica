package fi.dy.masa.litematica.render.schematic;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import com.google.common.collect.Sets;
import org.joml.Matrix4f;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.VertexSorter;
import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.render.*;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.chunk.WorldChunk;
import fi.dy.masa.malilib.util.Color4f;
import fi.dy.masa.malilib.util.EntityUtils;
import fi.dy.masa.malilib.util.IntBoundingBox;
import fi.dy.masa.malilib.util.LayerRange;
import fi.dy.masa.litematica.Litematica;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.render.RenderUtils;
import fi.dy.masa.litematica.render.cache.BufferBuilderCache;
import fi.dy.masa.litematica.render.cache.BufferBuilderPatch;
import fi.dy.masa.litematica.render.cache.MeshDataCache;
import fi.dy.masa.litematica.render.cache.SectionBufferCache;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager.PlacementPart;
import fi.dy.masa.litematica.util.OverlayType;
import fi.dy.masa.litematica.util.PositionUtils;
import fi.dy.masa.litematica.world.WorldSchematic;

public class ChunkRendererSchematicVbo
{
    public static int schematicRenderChunksUpdated;

    protected volatile WorldSchematic world;
    protected final WorldRendererSchematic worldRenderer;
    protected final ReentrantLock chunkRenderLock;
    protected final ReentrantLock chunkRenderDataLock;
    protected final Set<BlockEntity> setBlockEntities = new HashSet<>();
    protected final BlockPos.Mutable position;
    protected final BlockPos.Mutable chunkRelativePos;

    protected final Map<RenderLayer, VertexBuffer> vertexBufferBlocks;
    protected final Map<OverlayRenderType, VertexBuffer> vertexBufferOverlay;
    protected final List<IntBoundingBox> boxes = new ArrayList<>();
    protected final EnumSet<OverlayRenderType> existingOverlays = EnumSet.noneOf(OverlayRenderType.class);
    protected final SectionBufferCache sectionBufferCache;
    protected final MeshDataCache meshDataCache;

    private net.minecraft.util.math.Box boundingBox;
    protected Color4f overlayColor;
    protected boolean hasOverlay = false;
    private boolean ignoreClientWorldFluids;

    protected ChunkCacheSchematic schematicWorldView;
    protected ChunkCacheSchematic clientWorldView;

    protected ChunkRenderTaskSchematic compileTask;
    protected ChunkRenderDataSchematic chunkRenderData;

    private boolean needsUpdate;
    private boolean needsImmediateUpdate;

    public ChunkRendererSchematicVbo(WorldSchematic world, WorldRendererSchematic worldRenderer)
    {
        this.world = world;
        this.worldRenderer = worldRenderer;
        this.chunkRenderData = ChunkRenderDataSchematic.EMPTY;
        this.chunkRenderLock = new ReentrantLock();
        this.chunkRenderDataLock = new ReentrantLock();
        this.vertexBufferBlocks = new IdentityHashMap<>();
        this.vertexBufferOverlay = new IdentityHashMap<>();
        this.position = new BlockPos.Mutable();
        this.chunkRelativePos = new BlockPos.Mutable();
        this.sectionBufferCache = new SectionBufferCache();
        this.meshDataCache = new MeshDataCache();
    }

    public SectionBufferCache getSectionBufferCache()
    {
        return this.sectionBufferCache;
    }

    public MeshDataCache getMeshDataCache()
    {
        return this.meshDataCache;
    }

    public boolean hasOverlay()
    {
        return this.hasOverlay;
    }

    public EnumSet<OverlayRenderType> getOverlayTypes()
    {
        return this.existingOverlays;
    }

    public VertexBuffer getBlocksVertexBufferByLayer(RenderLayer layer)
    {
        Litematica.logger.error("getBlocksVertexBufferByLayer: [VBO] layer [{}]", layer.getDrawMode().name());

        return this.vertexBufferBlocks.computeIfAbsent(layer, l -> new VertexBuffer(VertexBuffer.Usage.STATIC));
    }

    public VertexBuffer getOverlayVertexBuffer(OverlayRenderType type)
    {
        Litematica.logger.error("getOverlayVertexBuffer: [VBO] type [{}]", type.getDrawMode().name());

        //if (GuiBase.isCtrlDown()) System.out.printf("getOverlayVertexBuffer: type: %s, buf: %s\n", type, this.vertexBufferOverlay[type.ordinal()]);
        return this.vertexBufferOverlay.computeIfAbsent(type, l -> new VertexBuffer(VertexBuffer.Usage.STATIC));
    }

    public ChunkRenderDataSchematic getChunkRenderData()
    {
        return this.chunkRenderData;
    }

    public void setChunkRenderData(ChunkRenderDataSchematic data)
    {
        this.chunkRenderDataLock.lock();

        try
        {
            this.chunkRenderData = data;
        }
        finally
        {
            this.chunkRenderDataLock.unlock();
        }
    }

    public BlockPos getOrigin()
    {
        return this.position;
    }

    public net.minecraft.util.math.Box getBoundingBox()
    {
        if (this.boundingBox == null)
        {
            int x = this.position.getX();
            int y = this.position.getY();
            int z = this.position.getZ();
            this.boundingBox = new net.minecraft.util.math.Box(x, y, z, x + 16, y + this.world.getHeight(), z + 16);
        }

        return this.boundingBox;
    }

    public void setPosition(int x, int y, int z)
    {
        if (x != this.position.getX() ||
            y != this.position.getY() ||
            z != this.position.getZ())
        {
            this.clear();
            this.boundingBox = null;
            this.position.set(x, y, z);
        }
    }

    protected double getDistanceSq()
    {
        Entity entity = EntityUtils.getCameraEntity();

        double x = this.position.getX() + 8.0D - entity.getX();
        double z = this.position.getZ() + 8.0D - entity.getZ();

        return x * x + z * z;
    }

    public void deleteGlResources()
    {
        this.clear();
        this.world = null;

        this.vertexBufferBlocks.values().forEach(VertexBuffer::close);
        this.vertexBufferOverlay.values().forEach(VertexBuffer::close);
    }

    public void resortTransparency(ChunkRenderTaskSchematic task)
    {
        Litematica.logger.warn("resortTransparency() [VBO]");

        RenderLayer layerTranslucent = RenderLayer.getTranslucent();
        ChunkRenderDataSchematic data = task.getChunkRenderData();
        BufferBuilderCache buffers = task.getBufferCache();
        //BufferBuilder.TransparentSortingData bufferState = data.getBlockBufferState(layerTranslucent);
        BuiltBuffer.SortState bufferState = data.getBlockSortState(layerTranslucent);
        Vec3d cameraPos = task.getCameraPosSupplier().get();

        float x = (float) cameraPos.x - this.position.getX();
        float y = (float) cameraPos.y - this.position.getY();
        float z = (float) cameraPos.z - this.position.getZ();

        if (bufferState != null)
        {
            if (data.isBlockLayerEmpty(layerTranslucent) == false)
            {
                //BufferBuilderPatch buffer = buffers.getBufferByLayer(layerTranslucent);

                RenderSystem.setShader(GameRenderer::getRenderTypeTranslucentProgram);

                //this.preRenderBlocks(buffer, layerTranslucent);
                BufferBuilderPatch buffer = this.preRenderBlocks(this.sectionBufferCache.getBufferByLayer(layerTranslucent), layerTranslucent);
                //buffer.beginSortedIndexBuffer(bufferState);

                this.postRenderBlocks(layerTranslucent, x, y, z, buffer.asBufferBuilder(), data);
                buffers.storeBufferByLayer(layerTranslucent, buffer);
            }
        }

        // We cannot use the SortState for OverlayRenderTypes

        //if (GuiBase.isCtrlDown()) System.out.printf("resortTransparency\n");
        //if (Configs.Visuals.ENABLE_SCHEMATIC_OVERLAY.getBooleanValue())
        //{
            OverlayRenderType type = OverlayRenderType.QUAD;
            //bufferState = data.getOverlayBufferState(type);

            if (data.isOverlayTypeEmpty(type) == false)
            {
                //BufferBuilderPatch buffer = buffers.getBufferByOverlay(type);

                BufferBuilderPatch buffer = this.preRenderOverlay(this.sectionBufferCache.getBufferByOverlay(type), type.getDrawMode());
                //buffer.beginSortedIndexBuffer(bufferState);

                this.postRenderOverlay(type, x, y, z, buffer.asBufferBuilder(), data);
                buffers.storeBufferByOverlay(type, buffer);
            }
        //}
    }

    public void rebuildChunk(ChunkRenderTaskSchematic task)
    {
        ChunkRenderDataSchematic data = new ChunkRenderDataSchematic();
        task.getLock().lock();

        try
        {
            if (task.getStatus() != ChunkRenderTaskSchematic.Status.COMPILING)
            {
                return;
            }

            task.setChunkRenderData(data);
        }
        finally
        {
            task.getLock().unlock();
        }

        Set<BlockEntity> tileEntities = new HashSet<>();
        BlockPos posChunk = this.position;
        LayerRange range = DataManager.getRenderLayerRange();

        this.existingOverlays.clear();
        this.hasOverlay = false;

        synchronized (this.boxes)
        {
            int minX = posChunk.getX();
            int minY = posChunk.getY();
            int minZ = posChunk.getZ();
            int maxX = minX + 15;
            int maxY = minY + this.world.getHeight();
            int maxZ = minZ + 15;

            if (this.boxes.isEmpty() == false &&
                (this.schematicWorldView.isEmpty() == false || this.clientWorldView.isEmpty() == false) &&
                 range.intersectsBox(minX, minY, minZ, maxX, maxY, maxZ))
            {
                ++schematicRenderChunksUpdated;

                Vec3d cameraPos = task.getCameraPosSupplier().get();
                float x = (float) cameraPos.x - this.position.getX();
                float y = (float) cameraPos.y - this.position.getY();
                float z = (float) cameraPos.z - this.position.getZ();
                Set<RenderLayer> usedLayers = new HashSet<>();
                BufferBuilderCache resultCache = task.getBufferCache();
                MatrixStack matrixStack = new MatrixStack();
                // TODO --> Do we need to change this to a Matrix4f in the future,
                //  when Matrix4f doesn't break things here or do we need to call RenderSystem again?
                int bottomY = this.position.getY();

                for (IntBoundingBox box : this.boxes)
                {
                    box = range.getClampedRenderBoundingBox(box);

                    // The rendered layer(s) don't intersect this sub-volume
                    if (box == null)
                    {
                        continue;
                    }

                    BlockPos posFrom = new BlockPos(box.minX, box.minY, box.minZ);
                    BlockPos posTo   = new BlockPos(box.maxX, box.maxY, box.maxZ);

                    for (BlockPos posMutable : BlockPos.Mutable.iterate(posFrom, posTo))
                    {
                        // Fluid models and the overlay use the VertexConsumer#vertex(x, y, z) method.
                        // Fluid rendering and the overlay do not use the MatrixStack.
                        // Block models use the VertexConsumer#quad() method, and they use the MatrixStack.
                        matrixStack.push();
                        matrixStack.translate(posMutable.getX() & 0xF, posMutable.getY() - bottomY, posMutable.getZ() & 0xF);

                        this.renderBlocksAndOverlay(posMutable, data, tileEntities, usedLayers, matrixStack.peek().getPositionMatrix(), resultCache);

                        matrixStack.pop();
                    }
                }

                for (RenderLayer layerTmp : RenderLayer.getBlockLayers())
                {
                    if (usedLayers.contains(layerTmp))
                    {
                        data.setBlockLayerUsed(layerTmp);
                    }

                    if (data.isBlockLayerStarted(layerTmp))
                    {
                        this.postRenderBlocks(layerTmp, x, y, z, resultCache.getBufferByLayer(layerTmp).asBufferBuilder(), data);
                        data.setBlockLayerUsed(layerTmp);
                    }
                }

                if (this.hasOverlay)
                {
                    //if (GuiBase.isCtrlDown()) System.out.printf("postRenderOverlays\n");
                    for (OverlayRenderType type : this.existingOverlays)
                    {
                        if (data.isOverlayTypeStarted(type))
                        {
                            this.postRenderOverlay(type, x, y, z, resultCache.getBufferByOverlay(type).asBufferBuilder(), data);
                            data.setOverlayTypeUsed(type);
                        }
                    }
                }
            }
        }

        this.chunkRenderLock.lock();

        try
        {
            Set<BlockEntity> set = Sets.newHashSet(tileEntities);
            Set<BlockEntity> set1 = Sets.newHashSet(this.setBlockEntities);
            set.removeAll(this.setBlockEntities);
            set1.removeAll(tileEntities);
            this.setBlockEntities.clear();
            this.setBlockEntities.addAll(tileEntities);
            this.worldRenderer.updateBlockEntities(set1, set);
        }
        finally
        {
            this.chunkRenderLock.unlock();
        }


        data.setTimeBuilt(this.world.getTime());
    }

    protected void renderBlocksAndOverlay(BlockPos pos, ChunkRenderDataSchematic data, Set<BlockEntity> tileEntities,
                                          Set<RenderLayer> usedLayers, Matrix4f matrix4f, @Nonnull BufferBuilderCache buffers)
    {
        Litematica.logger.warn("renderBlocksAndOverlay() [VBO] start");

        BlockState stateSchematic = this.schematicWorldView.getBlockState(pos);
        BlockState stateClient    = this.clientWorldView.getBlockState(pos);
        boolean clientHasAir = stateClient.isAir();
        boolean schematicHasAir = stateSchematic.isAir();
        boolean missing = false;

        if (clientHasAir && schematicHasAir)
        {
            return;
        }

        this.overlayColor = null;

        // Schematic has a block, client has air
        if (clientHasAir || (stateSchematic != stateClient && Configs.Visuals.RENDER_COLLIDING_SCHEMATIC_BLOCKS.getBooleanValue()))
        {
            if (stateSchematic.hasBlockEntity())
            {
                this.addBlockEntity(pos, data, tileEntities);
            }

            boolean translucent = Configs.Visuals.RENDER_BLOCKS_AS_TRANSLUCENT.getBooleanValue();
            // TODO change when the fluids become separate
            FluidState fluidState = stateSchematic.getFluidState();

            if (fluidState.isEmpty() == false)
            {
                RenderLayer layer = RenderLayers.getFluidLayer(fluidState);
                int offsetY = ((pos.getY() >> 4) << 4) - this.position.getY();

                //OmegaHackfixForCrashJustTemporarilyForNowISwearBecauseOfShittyBrokenCodeBufferBuilder bufferSchematic = buffers.getBlockBufferByLayer(layer);
                BufferBuilderPatch bufferSchematic;

                // FIXME
                if (data.isBlockLayerStarted(layer) == false)
                {
                    bufferSchematic = this.preRenderBlocks(this.sectionBufferCache.getBufferByLayer(layer), layer);
                    buffers.storeBufferByLayer(layer, bufferSchematic);
                    data.setBlockLayerStarted(layer);
                }
                else
                {
                    bufferSchematic = buffers.getBufferByLayer(layer);
                }
                bufferSchematic.setOffsetY(offsetY);
                // TODO

                this.worldRenderer.renderFluid(this.schematicWorldView, fluidState, pos, bufferSchematic.asBufferBuilder());
                usedLayers.add(layer);
                bufferSchematic.setOffsetY(0.0F);
            }

            if (stateSchematic.getRenderType() != BlockRenderType.INVISIBLE)
            {
                RenderLayer layer = translucent ? RenderLayer.getTranslucent() : RenderLayers.getBlockLayer(stateSchematic);
                BufferBuilderPatch buffer;

                // FIXME
                if (data.isBlockLayerStarted(layer) == false)
                {
                    buffer = this.preRenderBlocks(this.sectionBufferCache.getBufferByLayer(layer), layer);
                    buffers.storeBufferByLayer(layer, buffer);
                    data.setBlockLayerStarted(layer);
                }
                else
                {
                    buffer = buffers.getBufferByLayer(layer);
                }
                // TODO

                if (this.worldRenderer.renderBlock(this.schematicWorldView, stateSchematic, pos, matrix4f, buffer.asBufferBuilder()))
                {
                    usedLayers.add(layer);
                }

                if (clientHasAir)
                {
                    missing = true;
                }
            }
        }

        if (Configs.Visuals.ENABLE_SCHEMATIC_OVERLAY.getBooleanValue())
        {
            OverlayType type = this.getOverlayType(stateSchematic, stateClient);

            this.overlayColor = this.getOverlayColor(type);

            if (this.overlayColor != null)
            {
                this.renderOverlay(type, pos, stateSchematic, missing, data, buffers);
            }
        }
    }

    protected void renderOverlay(OverlayType type, BlockPos pos, BlockState stateSchematic, boolean missing, ChunkRenderDataSchematic data, @Nonnull BufferBuilderCache buffers)
    {
        Litematica.logger.warn("renderOverlay() [VBO] type [{}]", type.name());

        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        BlockPos.Mutable relPos = this.getChunkRelativePosition(pos);

        if (Configs.Visuals.SCHEMATIC_OVERLAY_ENABLE_SIDES.getBooleanValue())
        {
            BufferBuilderPatch bufferOverlayQuads;

            if (data.isOverlayTypeStarted(OverlayRenderType.QUAD) == false)
            {
                bufferOverlayQuads = this.preRenderOverlay(this.sectionBufferCache.getBufferByOverlay(OverlayRenderType.QUAD), OverlayRenderType.QUAD);
                buffers.storeBufferByOverlay(OverlayRenderType.QUAD, bufferOverlayQuads);
                data.setOverlayTypeStarted(OverlayRenderType.QUAD);
            }
            else
            {
                bufferOverlayQuads = buffers.getBufferByOverlay(OverlayRenderType.QUAD);
            }
            // TODO

            if (Configs.Visuals.OVERLAY_REDUCED_INNER_SIDES.getBooleanValue())
            {
                BlockPos.Mutable posMutable = new BlockPos.Mutable();

                for (int i = 0; i < 6; ++i)
                {
                    Direction side = fi.dy.masa.malilib.util.PositionUtils.ALL_DIRECTIONS[i];
                    posMutable.set(pos.getX() + side.getOffsetX(), pos.getY() + side.getOffsetY(), pos.getZ() + side.getOffsetZ());
                    BlockState adjStateSchematic = this.schematicWorldView.getBlockState(posMutable);
                    BlockState adjStateClient    = this.clientWorldView.getBlockState(posMutable);

                    OverlayType typeAdj = this.getOverlayType(adjStateSchematic, adjStateClient);

                    // Only render the model-based outlines or sides for missing blocks
                    if (missing && Configs.Visuals.SCHEMATIC_OVERLAY_MODEL_SIDES.getBooleanValue())
                    {
                        BakedModel bakedModel = this.worldRenderer.getModelForState(stateSchematic);

                        if (type.getRenderPriority() > typeAdj.getRenderPriority() ||
                            Block.isFaceFullSquare(stateSchematic.getCollisionShape(this.schematicWorldView, pos), side) == false)
                        {
                            RenderUtils.drawBlockModelQuadOverlayBatched(bakedModel, stateSchematic, relPos, side, this.overlayColor, 0, bufferOverlayQuads);
                        }
                    }
                    else
                    {
                        if (type.getRenderPriority() > typeAdj.getRenderPriority())
                        {
                            RenderUtils.drawBlockBoxSideBatchedQuads(relPos, side, this.overlayColor, 0, bufferOverlayQuads);
                        }
                    }
                }
            }
            else
            {
                // Only render the model-based outlines or sides for missing blocks
                if (missing && Configs.Visuals.SCHEMATIC_OVERLAY_MODEL_SIDES.getBooleanValue())
                {
                    BakedModel bakedModel = this.worldRenderer.getModelForState(stateSchematic);
                    RenderUtils.drawBlockModelQuadOverlayBatched(bakedModel, stateSchematic, relPos, this.overlayColor, 0, bufferOverlayQuads);
                }
                else
                {
                    fi.dy.masa.malilib.render.RenderUtils.drawBlockBoundingBoxSidesBatchedQuads(relPos, this.overlayColor, 0, bufferOverlayQuads);
                }
            }
        }

        if (Configs.Visuals.SCHEMATIC_OVERLAY_ENABLE_OUTLINES.getBooleanValue())
        {
            BufferBuilderPatch bufferOverlayOutlines;

            if (data.isOverlayTypeStarted(OverlayRenderType.OUTLINE) == false)
            {
                bufferOverlayOutlines = this.preRenderOverlay(this.sectionBufferCache.getBufferByOverlay(OverlayRenderType.OUTLINE), OverlayRenderType.OUTLINE);
                buffers.storeBufferByOverlay(OverlayRenderType.OUTLINE, bufferOverlayOutlines);
                data.setOverlayTypeStarted(OverlayRenderType.OUTLINE);
            }
            else
            {
                bufferOverlayOutlines = buffers.getBufferByOverlay(OverlayRenderType.OUTLINE);
            }
            // TODO

            this.overlayColor = new Color4f(this.overlayColor.r, this.overlayColor.g, this.overlayColor.b, 1f);

            if (Configs.Visuals.OVERLAY_REDUCED_INNER_SIDES.getBooleanValue())
            {
                OverlayType[][][] adjTypes = new OverlayType[3][3][3];
                BlockPos.Mutable posMutable = new BlockPos.Mutable();

                for (int y = 0; y <= 2; ++y)
                {
                    for (int z = 0; z <= 2; ++z)
                    {
                        for (int x = 0; x <= 2; ++x)
                        {
                            if (x != 1 || y != 1 || z != 1)
                            {
                                posMutable.set(pos.getX() + x - 1, pos.getY() + y - 1, pos.getZ() + z - 1);
                                BlockState adjStateSchematic = this.schematicWorldView.getBlockState(posMutable);
                                BlockState adjStateClient    = this.clientWorldView.getBlockState(posMutable);
                                adjTypes[x][y][z] = this.getOverlayType(adjStateSchematic, adjStateClient);
                            }
                            else
                            {
                                adjTypes[x][y][z] = type;
                            }
                        }
                    }
                }

                // Only render the model-based outlines or sides for missing blocks
                if (missing && Configs.Visuals.SCHEMATIC_OVERLAY_MODEL_OUTLINE.getBooleanValue())
                {
                    BakedModel bakedModel = this.worldRenderer.getModelForState(stateSchematic);

                    // FIXME: how to implement this correctly here... >_>
                    if (stateSchematic.isOpaque())
                    {
                        this.renderOverlayReducedEdges(pos, adjTypes, type, bufferOverlayOutlines);
                    }
                    else
                    {
                        RenderUtils.drawBlockModelOutlinesBatched(bakedModel, stateSchematic, relPos, this.overlayColor, 0, bufferOverlayOutlines);
                    }
                }
                else
                {
                    this.renderOverlayReducedEdges(pos, adjTypes, type, bufferOverlayOutlines);
                }
            }
            else
            {
                // Only render the model-based outlines or sides for missing blocks
                if (missing && Configs.Visuals.SCHEMATIC_OVERLAY_MODEL_OUTLINE.getBooleanValue())
                {
                    BakedModel bakedModel = this.worldRenderer.getModelForState(stateSchematic);
                    RenderUtils.drawBlockModelOutlinesBatched(bakedModel, stateSchematic, relPos, this.overlayColor, 0, bufferOverlayOutlines);
                }
                else
                {
                    fi.dy.masa.malilib.render.RenderUtils.drawBlockBoundingBoxOutlinesBatchedLines(relPos, this.overlayColor, 0, bufferOverlayOutlines);
                }
            }
        }
    }

    protected BlockPos.Mutable getChunkRelativePosition(BlockPos pos)
    {
        return this.chunkRelativePos.set(pos.getX() & 0xF, pos.getY() - this.position.getY(), pos.getZ() & 0xF);
    }

    protected void renderOverlayReducedEdges(BlockPos pos, OverlayType[][][] adjTypes, OverlayType typeSelf, BufferBuilderPatch bufferOverlayOutlines)
    {
        Litematica.logger.warn("renderOverlayReducedEdges() [VBO]");

        OverlayType[] neighborTypes = new OverlayType[4];
        Vec3i[] neighborPositions = new Vec3i[4];
        int lines = 0;

        for (Direction.Axis axis : PositionUtils.AXES_ALL)
        {
            for (int corner = 0; corner < 4; ++corner)
            {
                Vec3i[] offsets = PositionUtils.getEdgeNeighborOffsets(axis, corner);
                int index = -1;
                boolean hasCurrent = false;

                // Find the position(s) around a given edge line that have the shared greatest rendering priority
                for (int i = 0; i < 4; ++i)
                {
                    Vec3i offset = offsets[i];
                    OverlayType type = adjTypes[offset.getX() + 1][offset.getY() + 1][offset.getZ() + 1];

                    // type NONE
                    if (type == OverlayType.NONE)
                    {
                        continue;
                    }

                    // First entry, or sharing at least the current highest found priority
                    if (index == -1 || type.getRenderPriority() >= neighborTypes[index - 1].getRenderPriority())
                    {
                        // Actually a new highest priority, add it as the first entry and rewind the index
                        if (index < 0 || type.getRenderPriority() > neighborTypes[index - 1].getRenderPriority())
                        {
                            index = 0;
                        }
                        // else: Same priority as a previous entry, append this position

                        //System.out.printf("plop 0 axis: %s, corner: %d, i: %d, index: %d, type: %s\n", axis, corner, i, index, type);
                        neighborPositions[index] = new Vec3i(pos.getX() + offset.getX(), pos.getY() + offset.getY(), pos.getZ() + offset.getZ());
                        neighborTypes[index] = type;
                        // The self position is the first (offset = [0, 0, 0]) in the arrays
                        hasCurrent |= (i == 0);
                        ++index;
                    }
                }

                //System.out.printf("plop 1 index: %d, pos: %s\n", index, pos);
                // Found something to render, and the current block is among the highest priority for this edge
                if (index > 0 && hasCurrent)
                {
                    Vec3i posTmp = new Vec3i(pos.getX(), pos.getY(), pos.getZ());
                    int ind = -1;

                    for (int i = 0; i < index; ++i)
                    {
                        Vec3i tmp = neighborPositions[i];
                        //System.out.printf("posTmp: %s, tmp: %s\n", posTmp, tmp);

                        // Just prioritize the position to render a shared highest priority edge by the coordinates
                        if (tmp.getX() <= posTmp.getX() && tmp.getY() <= posTmp.getY() && tmp.getZ() <= posTmp.getZ())
                        {
                            posTmp = tmp;
                            ind = i;
                        }
                    }

                    // The current position is the one that should render this edge
                    if (posTmp.getX() == pos.getX() && posTmp.getY() == pos.getY() && posTmp.getZ() == pos.getZ())
                    {
                        //System.out.printf("plop 2 index: %d, ind: %d, pos: %s, off: %s\n", index, ind, pos, posTmp);
                        RenderUtils.drawBlockBoxEdgeBatchedLines(this.getChunkRelativePosition(pos), axis, corner, this.overlayColor, bufferOverlayOutlines);
                        lines++;
                    }
                }
            }
        }
        //System.out.printf("typeSelf: %s, pos: %s, lines: %d\n", typeSelf, pos, lines);
    }

    protected OverlayType getOverlayType(BlockState stateSchematic, BlockState stateClient)
    {
        if (stateSchematic == stateClient)
        {
            return OverlayType.NONE;
        }
        else
        {
            boolean clientHasAir = stateClient.isAir();
            boolean schematicHasAir = stateSchematic.isAir();

            // TODO --> Maybe someday Mojang will add something to replace isLiquid(), and isSolid(), someday?
            if (schematicHasAir)
            {
                return (clientHasAir || (this.ignoreClientWorldFluids && stateClient.isLiquid())) ? OverlayType.NONE : OverlayType.EXTRA;
            }
            else
            {
                if (clientHasAir || (this.ignoreClientWorldFluids && stateClient.isLiquid()))
                {
                    return OverlayType.MISSING;
                }
                // Wrong block
                else if (stateSchematic.getBlock() != stateClient.getBlock())
                {
                    return OverlayType.WRONG_BLOCK;
                }
                // Wrong state
                else
                {
                    return OverlayType.WRONG_STATE;
                }
            }
        }
    }

    @Nullable
    protected Color4f getOverlayColor(OverlayType overlayType)
    {
        Color4f overlayColor = null;

        switch (overlayType)
        {
            case MISSING:
                if (Configs.Visuals.SCHEMATIC_OVERLAY_TYPE_MISSING.getBooleanValue())
                {
                    overlayColor = Configs.Colors.SCHEMATIC_OVERLAY_COLOR_MISSING.getColor();
                }
                break;
            case EXTRA:
                if (Configs.Visuals.SCHEMATIC_OVERLAY_TYPE_EXTRA.getBooleanValue())
                {
                    overlayColor = Configs.Colors.SCHEMATIC_OVERLAY_COLOR_EXTRA.getColor();
                }
                break;
            case WRONG_BLOCK:
                if (Configs.Visuals.SCHEMATIC_OVERLAY_TYPE_WRONG_BLOCK.getBooleanValue())
                {
                    overlayColor = Configs.Colors.SCHEMATIC_OVERLAY_COLOR_WRONG_BLOCK.getColor();
                }
                break;
            case WRONG_STATE:
                if (Configs.Visuals.SCHEMATIC_OVERLAY_TYPE_WRONG_STATE.getBooleanValue())
                {
                    overlayColor = Configs.Colors.SCHEMATIC_OVERLAY_COLOR_WRONG_STATE.getColor();
                }
                break;
            default:
        }

        return overlayColor;
    }

    private void addBlockEntity(BlockPos pos, ChunkRenderDataSchematic chunkRenderData, Set<BlockEntity> blockEntities)
    {
        Litematica.logger.warn("addBlockEntity() [VBO]");

        BlockEntity te = this.schematicWorldView.getBlockEntity(pos, WorldChunk.CreationType.CHECK);

        if (te != null)
        {
            BlockEntityRenderer<BlockEntity> tesr = MinecraftClient.getInstance().getBlockEntityRenderDispatcher().get(te);

            if (tesr != null)
            {
                chunkRenderData.addBlockEntity(te);

                if (tesr.rendersOutsideBoundingBox(te))
                {
                    blockEntities.add(te);
                }
            }
        }
    }

    private BufferBuilderPatch preRenderBlocks(@Nonnull BufferAllocator buffer, RenderLayer layer)
    {
        Litematica.logger.warn("preRenderBlocks() [VBO] layer {}", layer.getDrawMode().name());

        return new BufferBuilderPatch(buffer, VertexFormat.DrawMode.QUADS, layer.getVertexFormat());
    }

    private VertexSorter createVertexSorter(float x, float y, float z)
    {
        return VertexSorter.byDistance(x, y, z);
    }

    private VertexSorter createVertexSorterVec3d(Vec3d cam)
    {
        return VertexSorter.byDistance((float) (cam.x), (float) (cam.y), (float) (cam.z));
    }

    private VertexSorter createVertexSorterWithOrigin(Vec3d cam, BlockPos origin)
    {
        return VertexSorter.byDistance((float)(cam.x - (double)origin.getX()), (float)(cam.y - (double)origin.getY()), (float)(cam.z - (double)origin.getZ()));
    }

    public void uploadSectionLayer(@Nonnull BuiltBuffer meshData, @Nonnull VertexBuffer vertexBuffer)
    {
        Litematica.logger.warn("uploadSectionLayer() [VBO] start (buffer size: {}) // MeshDraw [{}]", vertexBuffer.getVertexFormat().getVertexSizeByte(), meshData.getDrawParameters().mode().name());

        if (vertexBuffer.isClosed())
        {
            Litematica.logger.error("uploadSectionLayer: [VBO] error uploading MeshData (VertexBuffer is closed)");

            meshData.close();
            return;
        }
        vertexBuffer.bind();
        vertexBuffer.upload(meshData);
        VertexBuffer.unbind();

        Litematica.logger.warn("uploadSectionLayer() [VBO] END");
    }

    public void uploadSectionIndex(@Nonnull BufferAllocator.CloseableBuffer result, @Nonnull VertexBuffer vertexBuffer)
    {
        Litematica.logger.warn("uploadSectionIndex() [VBO] start (buffer size: {})", vertexBuffer.getVertexFormat().getVertexSizeByte());

        if (vertexBuffer.isClosed())
        {
            Litematica.logger.error("uploadSectionIndex: [VBO] error uploading MeshData SortState (VertexBuffer is closed)");

            result.close();
            return;
        }
        vertexBuffer.bind();
        vertexBuffer.uploadIndexBuffer(result);
        VertexBuffer.unbind();

        Litematica.logger.warn("uploadSectionIndex() [VBO] END");
    }

    private void postRenderBlocks(RenderLayer layer, float x, float y, float z, @Nonnull BufferBuilder buffer, ChunkRenderDataSchematic chunkRenderData)
    {
        Litematica.logger.warn("postRenderBlocks() [VBO]");

        if (layer == RenderLayer.getTranslucent() && chunkRenderData.isBlockLayerEmpty(layer) == false)
        {
            BuiltBuffer newMeshData = buffer.endNullable();

            if (newMeshData == null)
            {
                return;
            }
            if (layer.isTranslucent())
            {
                VertexSorter sorter = createVertexSorter(x, y, z);
                BuiltBuffer.SortState newState = newMeshData.sortQuads(this.sectionBufferCache.getBufferByLayer(layer), sorter);

                this.uploadSectionIndex(newState.sortAndStore(this.sectionBufferCache.getBufferByLayer(layer), sorter), this.getBlocksVertexBufferByLayer(layer));
                chunkRenderData.setBlockSortState(layer, newState);
            }

            this.uploadSectionLayer(newMeshData, this.getBlocksVertexBufferByLayer(layer));
            this.meshDataCache.storeMeshByLayer(layer, newMeshData);
            chunkRenderData.setBlockBuffer(layer, newMeshData);

            //buffer.setSorter(VertexSorter.byDistance(x, y, z));
            //chunkRenderData.setBlockBufferState(layer, buffer.getSortingData());
        }

        //buffer.end();
    }

    private BufferBuilderPatch preRenderOverlay(@Nonnull BufferAllocator buffer, OverlayRenderType type)
    {
        Litematica.logger.warn("preRenderOverlay() [VBO] 1 type [{}]", type.getDrawMode().name());

        this.existingOverlays.add(type);
        this.hasOverlay = true;

        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        //buffer.begin(type.getDrawMode(), VertexFormats.POSITION_COLOR);
        return new BufferBuilderPatch(buffer, type.getDrawMode(), type.getVertexFormat());
    }

    private BufferBuilderPatch preRenderOverlay(@Nonnull BufferAllocator buffer, VertexFormat.DrawMode drawMode)
    {
        Litematica.logger.warn("preRenderOverlay() [VBO] 2 type [{}]", drawMode.name());
        Tessellator tess;

        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        //buffer.begin(drawMode, VertexFormats.POSITION_COLOR);
        return new BufferBuilderPatch(buffer, drawMode, VertexFormats.POSITION_COLOR);
    }

    private void postRenderOverlay(OverlayRenderType type, float x, float y, float z, @Nonnull BufferBuilder buffer, ChunkRenderDataSchematic chunkRenderData)
    {
        Litematica.logger.warn("postRenderOverlay() [VBO] type [{}]", type.getDrawMode().name());

        RenderSystem.applyModelViewMatrix();
        if (type == OverlayRenderType.QUAD && chunkRenderData.isOverlayTypeEmpty(type) == false)
        {
            BuiltBuffer newMeshData = buffer.endNullable();

            if (newMeshData == null)
            {
                return;
            }

            // TODO Mojang removed the Sorting State's from all non-translucent layers.
            if (type.isTranslucent())
            {
                VertexSorter sorter = createVertexSorter(x, y, z);
                BuiltBuffer.SortState newState = newMeshData.sortQuads(this.sectionBufferCache.getBufferByOverlay(type), sorter);
                this.uploadSectionIndex(newState.sortAndStore(this.sectionBufferCache.getBufferByOverlay(type), sorter), this.getOverlayVertexBuffer(type));
                chunkRenderData.setOverlayBufferState(type, newState);
            }

            this.uploadSectionLayer(newMeshData, this.getOverlayVertexBuffer(type));
            this.meshDataCache.storeMeshByType(type, newMeshData);
            chunkRenderData.setOverlayBlockBuffer(type, newMeshData);

            //buffer.setSorter(VertexSorter.byDistance(x, y, z));
            //chunkRenderData.setOverlayBufferState(type, buffer.getSortingData());
        }

        //buffer.end();
    }

    public ChunkRenderTaskSchematic makeCompileTaskChunkSchematic(Supplier<Vec3d> cameraPosSupplier)
    {
        Litematica.logger.warn("makeCompileTaskChunkSchematic() [VBO] ");

        this.chunkRenderLock.lock();
        ChunkRenderTaskSchematic generator = null;

        try
        {
            //if (GuiBase.isCtrlDown()) System.out.printf("makeCompileTaskChunk()\n");
            this.finishCompileTask();
            this.rebuildWorldView();
            this.compileTask = new ChunkRenderTaskSchematic(this, ChunkRenderTaskSchematic.Type.REBUILD_CHUNK, cameraPosSupplier, this.getDistanceSq());
            generator = this.compileTask;
        }
        finally
        {
            this.chunkRenderLock.unlock();
        }

        return generator;
    }

    @Nullable
    public ChunkRenderTaskSchematic makeCompileTaskTransparencySchematic(Supplier<Vec3d> cameraPosSupplier)
    {
        Litematica.logger.warn("makeCompileTaskTransparencySchematic() [VBO] ");

        this.chunkRenderLock.lock();

        try
        {
            if (this.compileTask == null || this.compileTask.getStatus() != ChunkRenderTaskSchematic.Status.PENDING)
            {
                if (this.compileTask != null && this.compileTask.getStatus() != ChunkRenderTaskSchematic.Status.DONE)
                {
                    this.compileTask.finish();
                }

                this.compileTask = new ChunkRenderTaskSchematic(this, ChunkRenderTaskSchematic.Type.RESORT_TRANSPARENCY, cameraPosSupplier, this.getDistanceSq());
                this.compileTask.setChunkRenderData(this.chunkRenderData);

                return this.compileTask;
            }
        }
        finally
        {
            this.chunkRenderLock.unlock();
        }

        return null;
    }

    protected void finishCompileTask()
    {
        Litematica.logger.warn("finishCompileTask() [VBO] ");

        this.chunkRenderLock.lock();

        try
        {
            if (this.compileTask != null && this.compileTask.getStatus() != ChunkRenderTaskSchematic.Status.DONE)
            {
                this.compileTask.finish();
                this.compileTask = null;
            }
        }
        finally
        {
            this.chunkRenderLock.unlock();
        }
    }

    public ReentrantLock getLockCompileTask()
    {
        return this.chunkRenderLock;
    }

    public void clear()
    {
        Litematica.logger.warn("clear() [VBO] ");

        this.finishCompileTask();
        this.sectionBufferCache.clearAll();
        this.meshDataCache.clear();
        this.chunkRenderData = ChunkRenderDataSchematic.EMPTY;
        //this.needsUpdate = true;
    }

    public void setNeedsUpdate(boolean immediate)
    {
        if (this.needsUpdate)
        {
            immediate |= this.needsImmediateUpdate;
        }

        this.needsUpdate = true;
        this.needsImmediateUpdate = immediate;
    }

    public void clearNeedsUpdate()
    {
        this.needsUpdate = false;
        this.needsImmediateUpdate = false;
    }

    public boolean needsUpdate()
    {
        return this.needsUpdate;
    }

    public boolean needsImmediateUpdate()
    {
        return this.needsUpdate && this.needsImmediateUpdate;
    }

    private void rebuildWorldView()
    {
        synchronized (this.boxes)
        {
            this.ignoreClientWorldFluids = Configs.Visuals.IGNORE_EXISTING_FLUIDS.getBooleanValue();
            ClientWorld worldClient = MinecraftClient.getInstance().world;
            this.schematicWorldView = new ChunkCacheSchematic(this.world, worldClient, this.position, 2);
            this.clientWorldView    = new ChunkCacheSchematic(worldClient, worldClient, this.position, 2);
            this.boxes.clear();

            int chunkX = this.position.getX() >> 4;
            int chunkZ = this.position.getZ() >> 4;

            for (PlacementPart part : DataManager.getSchematicPlacementManager().getPlacementPartsInChunk(chunkX, chunkZ))
            {
                this.boxes.add(part.bb);
            }
        }
    }

    public enum OverlayRenderType
    {
        OUTLINE     (VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.DEBUG_LINES, RenderLayer.DEFAULT_BUFFER_SIZE, false, false),
        QUAD        (VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.QUADS, RenderLayer.DEFAULT_BUFFER_SIZE, false, true);

        private final VertexFormat vertexFormat;
        private final VertexFormat.DrawMode drawMode;
        private final int bufferSize;
        private final boolean hasCrumbling;
        private final boolean translucent;

        OverlayRenderType(VertexFormat format, VertexFormat.DrawMode drawMode, int bufferSize, boolean crumbling, boolean translucent)
        {
            this.vertexFormat = format;
            this.drawMode = drawMode;
            this.bufferSize = bufferSize;
            this.hasCrumbling = crumbling;
            this.translucent = translucent;
        }

        public VertexFormat getVertexFormat() { return this.vertexFormat; }

        public VertexFormat.DrawMode getDrawMode()
        {
            return this.drawMode;
        }

        public int getExpectedBufferSize() { return this.bufferSize; }

        public boolean hasCrumbling() { return this.hasCrumbling; }

        public boolean isTranslucent() { return this.translucent; }
    }
}
