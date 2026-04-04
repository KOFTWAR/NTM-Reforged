package com.hbm.explosion;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/** * 处理核爆过程的工具类 (优化重制版)
 * 适配 Minecraft Forge 1.20.1
 */
public class ExplosionNukeRayBatched implements IExplosionRay {
    private final HashMap<ChunkPos, List<Vec3>> perChunk = new HashMap<>();
    private final List<ChunkPos> orderedChunks = new ArrayList<>();
    private final CoordComparator comparator = new CoordComparator();
    private final BlockPos pos;
    private final ChunkPos chunkPos;
    private final Level level;

    private final int strength;
    private final int radius;
    private final int speed;
    private final int gspNumMax;
    private int gspNum;

    private double theta;
    private double phi;
    private final double cachedSqrtGspNumMax;

    private boolean isAusf3Complete = false;

    public ExplosionNukeRayBatched(Level level, BlockPos pos, int strength, int speed, int radius) {
        this.level = level;
        this.pos = pos;
        this.chunkPos = new ChunkPos(pos);
        this.strength = strength;
        this.radius = radius;
        this.speed = speed;
        this.gspNumMax = (int)(2.5 * Math.PI * strength * strength);
        this.cachedSqrtGspNumMax = Math.sqrt(this.gspNumMax);
        this.gspNum = 1;
        this.theta = Math.PI;
        this.phi = 0.0;
    }

    public void collectTip(int count) {
        if (isAusf3Complete) return;

        int amountProcessed = 0;
        final double startX = pos.getX();
        final double startY = pos.getY();
        final double startZ = pos.getZ();
        final int length = (int) Math.ceil(strength);
        final double stepFac = 7.0 / length;

        BlockPos.MutableBlockPos dirPos = new BlockPos.MutableBlockPos();
        List<ChunkPos> rayChunks = new ArrayList<>(8);

        while (this.gspNumMax >= this.gspNum) {
            double dx = Math.sin(theta) * Math.cos(phi);
            double dy = Math.cos(theta);
            double dz = Math.sin(theta) * Math.sin(phi);

            float res = strength;
            Vec3 endPoint = null;
            rayChunks.clear();
            ChunkPos lastChunk = null;

            for (int i = 0; i < length && i < this.radius && res > 0; i++) {
                double cx = startX + dx * i;
                double cy = startY + dy * i;
                double cz = startZ + dz * i;

                dirPos.set((int) Math.floor(cx), (int) Math.floor(cy), (int) Math.floor(cz));
                BlockState blockState = level.getBlockState(dirPos);

                // 1.20.1 修复：仅使用 getFluidState() 判断流体
                if (blockState.getFluidState().isEmpty()) {
                    double exponent = 0.5 + i * stepFac;
                    res -= (float) Math.pow(blockState.getBlock().getExplosionResistance(), exponent);
                }

                if (res > 0 && !blockState.isAir()) {
                    endPoint = new Vec3(cx, cy, cz);
                    int chunkX = dirPos.getX() >> 4;
                    int chunkZ = dirPos.getZ() >> 4;
                    if (lastChunk == null || lastChunk.x != chunkX || lastChunk.z != chunkZ) {
                        lastChunk = new ChunkPos(chunkX, chunkZ);
                        rayChunks.add(lastChunk);
                    }
                }
            }

            if (endPoint != null) {
                for (int i = 0; i < rayChunks.size(); i++) {
                    perChunk.computeIfAbsent(rayChunks.get(i), k -> new ArrayList<>()).add(endPoint);
                }
            }

            this.generateGspUp();

            if (++amountProcessed >= count) {
                return; // 提前返回，等待下一个 Tick 继续计算
            }
        }

        // 显式分离完成逻辑，防呆并提高可读性
        completeCollection();
    }

    private void completeCollection() {
        orderedChunks.addAll(perChunk.keySet());
        orderedChunks.sort(comparator);
        isAusf3Complete = true;
    }

    public void processChunk() {
        if (this.perChunk.isEmpty() || this.orderedChunks.isEmpty()) return; // 增加 orderedChunks 空检查兜底

        ChunkPos coord = orderedChunks.remove(0);
        List<Vec3> list = perChunk.remove(coord);
        if (list == null) return;

        LongOpenHashSet toRem = new LongOpenHashSet();
        LongOpenHashSet toRemTips = new LongOpenHashSet();

        int enter = (int) (Math.min(Math.abs(pos.getX() - (coord.x << 4)), Math.abs(pos.getZ() - (coord.z << 4)))) - 16;
        enter = Math.max(enter, 0);

        BlockPos.MutableBlockPos pos1 = new BlockPos.MutableBlockPos();
        Vec3 posCenter = pos.getCenter();
        final double posX = pos.getX();
        final double posY = pos.getY();
        final double posZ = pos.getZ();

        for (Vec3 triplet : list) {
            double vx = triplet.x - posCenter.x;
            double vy = triplet.y - posCenter.y;
            double vz = triplet.z - posCenter.z;

            double vecLen = Math.sqrt(vx * vx + vy * vy + vz * vz);
            vx /= vecLen;
            vy /= vecLen;
            vz /= vecLen;

            long tipLong = BlockPos.asLong((int) Math.floor(triplet.x), (int) Math.floor(triplet.y), (int) Math.floor(triplet.z));
            boolean inChunk = false;

            for (int i = enter; i < vecLen; i++) {
                int px = (int) Math.floor(posX + vx * i);
                int py = (int) Math.floor(posY + vy * i);
                int pz = (int) Math.floor(posZ + vz * i);

                if ((px >> 4) != coord.x || (pz >> 4) != coord.z) {
                    if (inChunk) break;
                    else continue;
                }
                inChunk = true;

                pos1.set(px, py, pz);
                if (!level.getBlockState(pos1).isAir()) {
                    long posLong = pos1.asLong();
                    if (posLong == tipLong) {
                        toRemTips.add(posLong);
                    }
                    toRem.add(posLong);
                }
            }
        }

        BlockState airState = Blocks.AIR.defaultBlockState();
        LongIterator iter = toRem.iterator();
        while (iter.hasNext()) {
            long p = iter.nextLong();
            pos1.set(p);
            if (toRemTips.contains(p)) {
                this.handleTip(pos1);
            } else {
                // 1.20.1 修复：恢复 3 参数签名 (Flag 2: UPDATE_CLIENTS)
                level.setBlock(pos1, airState, 2);
            }
        }
    }

    protected void handleTip(BlockPos blockPos) {
        // Flag 3: UPDATE_ALL (触发邻居更新以处理边缘物理效果)
        level.setBlock(blockPos, Blocks.AIR.defaultBlockState(), 3);
    }

    private void generateGspUp() {
        if (this.gspNum < this.gspNumMax) {
            int k = this.gspNum + 1;
            double hk = -1.0 + 2.0 * (k - 1.0) / (this.gspNumMax - 1.0);
            this.theta = Math.acos(hk);

            double prev_lon = this.phi;
            // 数学优化修复：增加 max() 防止 hk 接近 1 时除以 0 导致 NaN
            double safeSqrt = Math.sqrt(Math.max(0.000001, 1.0 - hk * hk));
            double lon = prev_lon + 3.6 / cachedSqrtGspNumMax / safeSqrt;
            this.phi = lon % (Math.PI * 2);
        } else {
            this.theta = 0.0;
            this.phi = 0.0;
        }
        this.gspNum++;
    }

    @Override
    public void cacheChunksTick(int processTimeMs) {
        if (!isAusf3Complete) {
            collectTip(speed * 10);
        }
    }

    @Override
    public void destructionTick(int processTimeMs) {
        if (!isAusf3Complete) return;
        long start = System.currentTimeMillis();
        while (!perChunk.isEmpty() && System.currentTimeMillis() < start + processTimeMs) {
            processChunk();
        }
    }

    @Override
    public void cancel() {
        isAusf3Complete = true;
        perChunk.clear();
        orderedChunks.clear();
    }

    @Override
    public boolean isComplete() {
        return isAusf3Complete && perChunk.isEmpty();
    }

    public class CoordComparator implements Comparator<ChunkPos> {
        @Override
        public int compare(ChunkPos o1, ChunkPos o2) {
            int diff1 = Math.abs((chunkPos.x - o1.x)) + Math.abs((chunkPos.z - o1.z));
            int diff2 = Math.abs((chunkPos.x - o2.x)) + Math.abs((chunkPos.z - o2.z));
            return Integer.compare(diff1, diff2);
        }
    }
}