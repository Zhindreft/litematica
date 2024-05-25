package fi.dy.masa.litematica.render.schematic;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import org.apache.logging.log4j.Logger;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.entity.Entity;
import net.minecraft.util.crash.CrashReport;
import fi.dy.masa.litematica.Litematica;
import fi.dy.masa.litematica.render.cache.BufferBuilderCache;
import fi.dy.masa.litematica.render.schematic.ChunkRendererSchematicVbo.OverlayRenderType;

public class ChunkRenderWorkerLitematica implements Runnable
{
    private static final Logger LOGGER = Litematica.logger;

    private final ChunkRenderDispatcherLitematica chunkRenderDispatcher;
    private final BufferBuilderCache resultCache;
    private boolean shouldRun;

    public ChunkRenderWorkerLitematica(ChunkRenderDispatcherLitematica chunkRenderDispatcherIn)
    {
        this(chunkRenderDispatcherIn, null);
    }

    public ChunkRenderWorkerLitematica(ChunkRenderDispatcherLitematica chunkRenderDispatcherIn, @Nullable BufferBuilderCache buffers)
    {
        this.shouldRun = true;
        this.chunkRenderDispatcher = chunkRenderDispatcherIn;
        this.resultCache = buffers;
    }

    @Override
    public void run()
    {
        while (this.shouldRun)
        {
            try
            {
                this.processTask(this.chunkRenderDispatcher.getNextChunkUpdate());
            }
            catch (InterruptedException e)
            {
                LOGGER.debug("Stopping chunk worker due to interrupt");
                return;
            }
            catch (Throwable throwable)
            {
                CrashReport crashreport = CrashReport.create(throwable, "Batching chunks");
                MinecraftClient.getInstance().setCrashReportSupplier(MinecraftClient.getInstance().addDetailsToCrashReport(crashreport));
                return;
            }
        }
    }

    protected void processTask(final ChunkRenderTaskSchematic task) throws InterruptedException
    {
        task.getLock().lock();

        Litematica.logger.warn("processTask()");

        try
        {
            if (task.getStatus() != ChunkRenderTaskSchematic.Status.PENDING)
            {
                if (task.isFinished() == false)
                {
                    LOGGER.warn("Chunk render task was {} when I expected it to be pending; ignoring task", (Object) task.getStatus());
                }

                return;
            }

            task.setStatus(ChunkRenderTaskSchematic.Status.COMPILING);
        }
        finally
        {
            task.getLock().unlock();
        }

        Entity entity = MinecraftClient.getInstance().getCameraEntity();

        if (entity == null)
        {
            task.finish();
        }
        else
        {
            task.setRegionRenderCacheBuilder(this.getRegionRenderBufferCache());

            ChunkRenderTaskSchematic.Type taskType = task.getType();

            if (taskType == ChunkRenderTaskSchematic.Type.REBUILD_CHUNK)
            {
                task.getRenderChunk().rebuildChunk(task);
            }
            else if (taskType == ChunkRenderTaskSchematic.Type.RESORT_TRANSPARENCY)
            {
                task.getRenderChunk().resortTransparency(task);
            }

            task.getLock().lock();

            try
            {
                if (task.getStatus() != ChunkRenderTaskSchematic.Status.COMPILING)
                {
                    if (task.isFinished() == false)
                    {
                        LOGGER.warn("Chunk render task was {} when I expected it to be compiling; aborting task", (Object) task.getStatus());
                    }

                    this.freeRenderBufferCache(task);
                    return;
                }

                task.setStatus(ChunkRenderTaskSchematic.Status.UPLOADING);
            }
            finally
            {
                task.getLock().unlock();
            }

            final ChunkRenderDataSchematic chunkRenderData = (ChunkRenderDataSchematic) task.getChunkRenderData();
            ArrayList<ListenableFuture<Object>> futuresList = Lists.newArrayList();
            ChunkRendererSchematicVbo renderChunk = (ChunkRendererSchematicVbo) task.getRenderChunk();

            if (taskType == ChunkRenderTaskSchematic.Type.REBUILD_CHUNK)
            {
                //if (GuiBase.isCtrlDown()) System.out.printf("pre uploadChunk()\n");
                for (RenderLayer layer : RenderLayer.getBlockLayers())
                {
                    if (chunkRenderData.isBlockLayerEmpty(layer) == false)
                    {
                        //if (GuiBase.isCtrlDown()) System.out.printf("REBUILD_CHUNK pre uploadChunkBlocks()\n");
                        //System.out.printf("REBUILD_CHUNK pre uploadChunkBlocks(%s)\n", layer.toString());
                        //BufferBuilder buffer = buffers.getResultByLayer(layer);
                        // TODO
                        futuresList.add(this.chunkRenderDispatcher.uploadChunkBlocks(layer, renderChunk, chunkRenderData, task.getDistanceSq()));
                    }
                }

                for (OverlayRenderType type : OverlayRenderType.values())
                {
                    if (chunkRenderData.isOverlayTypeEmpty(type) == false)
                    {
                        //if (GuiBase.isCtrlDown()) System.out.printf("REBUILD_CHUNK pre uploadChunkOverlay()\n");
                        //BufferBuilder buffer = buffers.getOverlayBuffer(type);
                        // TODO
                        futuresList.add(this.chunkRenderDispatcher.uploadChunkOverlay(type, renderChunk, chunkRenderData, task.getDistanceSq()));
                    }
                }
            }
            else if (taskType == ChunkRenderTaskSchematic.Type.RESORT_TRANSPARENCY)
            {
                RenderLayer layer = RenderLayer.getTranslucent();

                if (chunkRenderData.isBlockLayerEmpty(layer) == false)
                {
                    //System.out.printf("RESORT_TRANSPARENCY pre uploadChunkBlocks(%s)\n", layer.toString());
                    //BufferBuilder buffer = buffers.getResultByLayer(layer);
                    // TODO
                    futuresList.add(this.chunkRenderDispatcher.uploadChunkBlocks(RenderLayer.getTranslucent(), renderChunk, chunkRenderData, task.getDistanceSq()));
                }

                if (chunkRenderData.isOverlayTypeEmpty(OverlayRenderType.QUAD) == false)
                {
                    //if (GuiBase.isCtrlDown()) System.out.printf("RESORT_TRANSPARENCY pre uploadChunkOverlay()\n");
                    //BufferBuilder buffer = buffers.getOverlayBuffer(OverlayRenderType.QUAD);
                    // TODO
                    futuresList.add(this.chunkRenderDispatcher.uploadChunkOverlay(OverlayRenderType.QUAD, renderChunk, chunkRenderData, task.getDistanceSq()));
                }
            }

            final ListenableFuture<List<Object>> listenablefuture = Futures.allAsList(futuresList);

            task.addFinishRunnable(new Runnable()
            {
                @Override
                public void run()
                {
                    listenablefuture.cancel(false);
                }
            });

            Futures.addCallback(listenablefuture, new FutureCallback<List<Object>>()
            {
                @Override
                public void onSuccess(@Nullable List<Object> list)
                {
                    ChunkRenderWorkerLitematica.this.freeRenderBufferCache(task);

                    task.getLock().lock();

                    label49:
                    {
                        try
                        {
                            if (task.getStatus() == ChunkRenderTaskSchematic.Status.UPLOADING)
                            {
                                task.setStatus(ChunkRenderTaskSchematic.Status.DONE);
                                break label49;
                            }

                            if (task.isFinished() == false)
                            {
                                ChunkRenderWorkerLitematica.LOGGER.warn("Chunk render task was {} when I expected it to be uploading; aborting task", (Object)task.getStatus());
                            }
                        }
                        finally
                        {
                            task.getLock().unlock();
                        }

                        return;
                    }

                    task.getRenderChunk().setChunkRenderData(chunkRenderData);
                }

                @Override
                public void onFailure(Throwable throwable)
                {
                    ChunkRenderWorkerLitematica.this.freeRenderBufferCache(task);

                    if ((throwable instanceof CancellationException) == false && (throwable instanceof InterruptedException) == false)
                    {
                        MinecraftClient.getInstance().setCrashReportSupplier(CrashReport.create(throwable, "Rendering Litematica chunk"));
                    }
                }
            }, MoreExecutors.directExecutor());
        }
    }

    private BufferBuilderCache getRegionRenderBufferCache() throws InterruptedException
    {
        Litematica.logger.warn("getRegionRenderBufferCache()");

        return this.resultCache != null ? this.resultCache : this.chunkRenderDispatcher.allocateRenderBuffers();
    }

    private void freeRenderBufferCache(ChunkRenderTaskSchematic generator)
    {
        Litematica.logger.warn("freeRenderBuffer()");

        BufferBuilderCache builderCache = generator.getBufferCache();
        builderCache.clear();

        if (this.resultCache == null)
        {
            this.chunkRenderDispatcher.freeRenderResults(builderCache);
        }
    }

    public void notifyToStop()
    {
        this.shouldRun = false;
    }
}
