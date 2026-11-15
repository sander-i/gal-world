/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.service;

import io.github.sander_i.domain.Game;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 与 deploy/bin/galworld 完全一致的分配规则：
 * display 取最小空闲（从 2 起），port = 9000 + display，
 * loopback 通道取最小的空闲 (card, slot) 组合。
 * 两种入口共享同一规则，才不会互相抢占。
 */
@Component
public class GameSlotAllocator {

    public record Slots(int displayNum, int port, int loopbackCard, int loopbackSlot) {
    }

    public static final int MIN_DISPLAY = 2;
    public static final int MAX_DISPLAY = 63;

    /**
     * snd-aloop 每个设备可用的 subdevice 数量上限。
     *
     * 内核参数 {@code pcm_substreams} 的合法范围就是 1-8，模块默认 8，
     * 也就是**单张 loopback 卡**最多同时服务 8 个游戏（每游戏独占一个
     * subdevice 做播放→采集配对）。超出后 module-alsa-source 加载失败，
     * 游戏在播放 BGM 时抛异常退出。
     *
     * 扩容靠多装几张卡：{@code modprobe snd-aloop index=0,1,2,3}，
     * 每张卡各有 8 个通道，于是总容量 = 卡数 × 8。
     */
    public static final int SLOTS_PER_CARD = 8;

    /** 与部署脚本默认加载的卡数一致（index=0,1） */
    public static final int LOOPBACK_CARDS = 2;

    public Slots allocate(List<Game> existing) {
        Set<Integer> displays = new HashSet<>();
        Set<Integer> ports = new HashSet<>();
        Set<Long> channels = new HashSet<>();
        for (Game g : existing) {
            displays.add(g.getDisplayNum());
            ports.add(g.getPort());
            channels.add(channelKey(g.getLoopbackCard(), g.getLoopbackSlot()));
        }
        int display = MIN_DISPLAY;
        while (display <= MAX_DISPLAY
                && (displays.contains(display) || ports.contains(9000 + display))) {
            display++;
        }
        if (display > MAX_DISPLAY) {
            throw new IllegalStateException("没有空闲的显示号/槽位");
        }

        // 先在同一张卡内找空位，卡内满了再换下一张，保证早期的游戏都留在 card 0
        for (int card = 0; card < LOOPBACK_CARDS; card++) {
            for (int slot = 0; slot < SLOTS_PER_CARD; slot++) {
                if (!channels.contains(channelKey(card, slot))) {
                    return new Slots(display, 9000 + display, card, slot);
                }
            }
        }
        throw new IllegalStateException(
                "loopback 通道已用满（" + LOOPBACK_CARDS + " 张卡 × " + SLOTS_PER_CARD
                        + " 个 = " + (LOOPBACK_CARDS * SLOTS_PER_CARD)
                        + " 个），无法再接新游戏");
    }

    private static long channelKey(int card, int slot) {
        return ((long) card << 32) | (slot & 0xffffffffL);
    }
}
