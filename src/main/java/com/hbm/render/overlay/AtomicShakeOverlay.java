package com.hbm.render.overlay;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.util.Mth;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;

public class AtomicShakeOverlay {
    public static final int shakeDuration = 1_500;  // 晃动持续时间：1.5秒
    public static long shakeTimestamp;              // 晃动开始时间戳

    private AtomicShakeOverlay() {
    }

    public static void trigger(RenderGuiOverlayEvent.Pre event) {
        // 检查是否在晃动持续时间内
        // 计算衰减系数（从2线性衰减到0）
        double mult = (shakeTimestamp + shakeDuration - System.currentTimeMillis()) / (double) shakeDuration * 2;
        // 水平晃动计算：
        // 1. sin(时间*0.02) 产生约0.0318Hz的频率（周期约31.4秒）
        // 2. clamp到[-0.7, 0.7]范围防止过度晃动
        // 3. 乘以15像素作为最大幅度
        double horizontal = Mth.clamp(
                Math.sin(System.currentTimeMillis() * 0.02), -0.7, 0.7) * 15;

        // 垂直晃动计算：
        // 1. sin(时间*0.01 + 2) 产生约0.0159Hz的频率（周期约62.8秒）
        // 2. 相位偏移2弧度
        // 3. clamp到[-0.7, 0.7]范围
        // 4. 乘以3像素作为最大幅度（垂直晃动较小）
        double vertical = Mth.clamp(
                Math.sin(System.currentTimeMillis() * 0.01 + 2), -0.7, 0.7) * 3;
        PoseStack poseStack = event.getGuiGraphics().pose();

        poseStack.translate(horizontal * mult, vertical * mult, 0);

    }

}