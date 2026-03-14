package com.hbm.explosion;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/** * 处理核爆过程的工具类 (优化重制版)
 * 适配 Minecraft Forge 1.21.1
 */
public class ExplosionNukeRayBatched implements IExplosionRay {
    // 存储每个区块待处理的射线端点
    private final HashMap<ChunkPos, List<Vec3>> perChunk = new HashMap<>();
    private final List<ChunkPos> orderedChunks = new ArrayList<>();
    private final CoordComparator comparator = new CoordComparator();
    private final BlockPos pos;       // 爆炸中心所在的位置（实体）
    private final ChunkPos chunkPos;  // 爆炸中心所在的区块

    private final Level level;

    private final int strength;
    private final int radius;
    private final int speed;
    private final int gspNumMax;
    private int gspNum;

    // 球坐标系的方位角
    private double theta;
    private double phi;

    // 缓存开方计算，避免在生成点时重复计算
    private final double cachedSqrtGspNumMax;

    private boolean isAusf3Complete = false;

    public ExplosionNukeRayBatched(Level level, BlockPos pos, int strength, int speed, int radius) {
        this.level = level;
        this.pos = pos;
        this.chunkPos = new ChunkPos(pos);
        this.strength = strength;
        this.radius = radius;
        this.speed = speed;
        // Total number of points (总射线点数)
        this.gspNumMax = (int)(2.5 * Math.PI * strength * strength);
        this.cachedSqrtGspNumMax = Math.sqrt(this.gspNumMax);
        this.gspNum = 1;

        // The beginning of the generalized spiral points (螺旋点起点)
        this.theta = Math.PI;
        this.phi = 0.0;
    }

    public void collectTip(int count) {
        int amountProcessed = 0;

        // 提取起始坐标，避免在循环中不断调用 getter
        final double startX = pos.getX();
        final double startY = pos.getY();
        final double startZ = pos.getZ();

        final int length = (int) Math.ceil(strength);
        // 化简原代码中的 fac 计算: 100 - (i / length) * 100 乘以 0.07
        // 7.5 - fac 可以化简为: 0.5 + 7.0 * (i / length)。这里提取步长系数以优化循环内性能
        final double stepFac = 7.0 / length;

        // 使用可变坐标，极大地减少循环中 new BlockPos() 带来的 GC 开销
        BlockPos.MutableBlockPos dirPos = new BlockPos.MutableBlockPos();
        // 存储一条射线穿过的区块，避免使用 HashSet
        List<ChunkPos> rayChunks = new ArrayList<>(8);

        while (this.gspNumMax >= this.gspNum) {
            // 将球坐标方位角转换成对应直角坐标方向向量 (内联化简，去除额外的 Vec2/Vec3 对象分配)
            double dx = Math.sin(theta) * Math.cos(phi);
            double dy = Math.cos(theta);
            double dz = Math.sin(theta) * Math.sin(phi);

            float res = strength;
            Vec3 endPoint = null;
            rayChunks.clear();
            ChunkPos lastChunk = null;

            // 沿着射线前进
            for (int i = 0; i < length && i < this.radius && res > 0; i++) {
                double cx = startX + dx * i;
                double cy = startY + dy * i;
                double cz = startZ + dz * i;

                dirPos.set((int) Math.floor(cx), (int) Math.floor(cy), (int) Math.floor(cz));
                BlockState blockState = level.getBlockState(dirPos);

                // 注意：这里移除了原代码中 level.setBlock(pos, Blocks.AIR...) 的 Bug 代码
                // 原代码错误地将中心点(pos)不断的设置为空气导致极大卡顿，清理可替换方块的任务应由 processChunk 完成

                if (!blockState.getFluidState().isEmpty() || blockState.liquid()) {
                    // 液体不扣除阻力
                } else {
                    // 使用化简后的指数公式计算爆炸抗性扣除
                    double exponent = 0.5 + i * stepFac;
                    res -= (float) Math.pow(blockState.getBlock().getExplosionResistance(), exponent);
                }

                if (res > 0 && !blockState.isAir()) {
                    endPoint = new Vec3(cx, cy, cz); // 只在阻力耗尽或边界时保留最后的 endPoint

                    // 只在跨越区块边界时才创建新的 ChunkPos 对象，效率远高于原先逐个方块加 HashSet 的做法
                    int chunkX = dirPos.getX() >> 4;
                    int chunkZ = dirPos.getZ() >> 4;
                    if (lastChunk == null || lastChunk.x != chunkX || lastChunk.z != chunkZ) {
                        lastChunk = new ChunkPos(chunkX, chunkZ);
                        rayChunks.add(lastChunk);
                    }
                }
            }

            // 将计算出的端点分配给它穿过的所有区块
            if (endPoint != null) {
                for (int i = 0; i < rayChunks.size(); i++) {
                    perChunk.computeIfAbsent(rayChunks.get(i), k -> new ArrayList<>()).add(endPoint);
                }
            }

            // 更新广义螺旋点
            this.generateGspUp();

            if (++amountProcessed >= count) {
                return;
            }
        }

        orderedChunks.addAll(perChunk.keySet());
        orderedChunks.sort(comparator);
        isAusf3Complete = true;
    }

    public void processChunk() {
        if (this.perChunk.isEmpty()) return;

        // 原代码用 get(0) 后 remove 会导致 ArrayList 复制数组，这里直接 remove(0) 性能更好
        ChunkPos coord = orderedChunks.remove(0);
        List<Vec3> list = perChunk.remove(coord);
        if (list == null) return;

        // 使用 FastUtil 的 LongSet 代替 HashSet<BlockPos>，内存占用极低，存取速度飞快
        LongOpenHashSet toRem = new LongOpenHashSet();
        LongOpenHashSet toRemTips = new LongOpenHashSet();

        // 跳过无需计算的空腔区域
        int enter = (int) (Math.min(Math.abs(pos.getX() - (coord.x << 4)), Math.abs(pos.getZ() - (coord.z << 4)))) - 16;
        enter = Math.max(enter, 0);

        BlockPos.MutableBlockPos pos1 = new BlockPos.MutableBlockPos();
        Vec3 posCenter = pos.getCenter();
        final double posX = pos.getX();
        final double posY = pos.getY();
        final double posZ = pos.getZ();

        for (Vec3 triplet : list) {
            // 原代码这里有坐标系的轻微偏差 (原点用了 getCenter() 但计算用了 getX())，这里保持原数学逻辑
            double vx = triplet.x - posCenter.x;
            double vy = triplet.y - posCenter.y;
            double vz = triplet.z - posCenter.z;

            // 手动归一化，避免多余的 Vec3 对象创建
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

                // 判断是否在当前区块内
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

        // 批量移除方块
        BlockState airState = Blocks.AIR.defaultBlockState();
        LongIterator iter = toRem.iterator();
        while (iter.hasNext()) {
            long p = iter.nextLong();
            pos1.set(p); // 将 long 还原为 MutableBlockPos 坐标
            if (toRemTips.contains(p)) {
                this.handleTip(pos1);
            } else {
                // 标志位 2 (UPDATE_CLIENTS) | 0，符合 1.21.1 原生设置机制
                level.setBlock(pos1, airState, 2, 0);
            }
        }
    }

    protected void handleTip(BlockPos blockPos) {
        level.setBlock(blockPos, Blocks.AIR.defaultBlockState(), 3);
    }

    // 更新方位角算法优化版
    private void generateGspUp() {
        if (this.gspNum < this.gspNumMax) {
            int k = this.gspNum + 1;
            double hk = -1.0 + 2.0 * (k - 1.0) / (this.gspNumMax - 1.0);
            this.theta = Math.acos(hk);

            // 使用缓存好的 cachedSqrtGspNumMax 进行计算
            double prev_lon = this.phi;
            double lon = prev_lon + 3.6 / cachedSqrtGspNumMax / Math.sqrt(1.0 - hk * hk);
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
        // 加入了 processTimeMs 的超时保护，避免单 tick 清理过多区块导致服务器未响应
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

    // 比较器，用于根据到中心点的曼哈顿距离对区块排序
    public class CoordComparator implements Comparator<ChunkPos> {
        @Override
        public int compare(ChunkPos o1, ChunkPos o2) {
            int diff1 = Math.abs((chunkPos.x - o1.x)) + Math.abs((chunkPos.z - o1.z));
            int diff2 = Math.abs((chunkPos.x - o2.x)) + Math.abs((chunkPos.z - o2.z));
            return Integer.compare(diff1, diff2);
        }
    }
}