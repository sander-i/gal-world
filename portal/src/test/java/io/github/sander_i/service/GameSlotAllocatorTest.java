/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.service;

import io.github.sander_i.domain.Game;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GameSlotAllocatorTest {

    private static Game game(int display, int slot) {
        return game(display, 0, slot);
    }

    private static Game game(int display, int card, int slot) {
        Game g = new Game();
        g.setDisplayNum(display);
        g.setPort(9000 + display);
        g.setLoopbackCard(card);
        g.setLoopbackSlot(slot);
        return g;
    }

    @Test
    void emptyRegistryStartsAtDisplay2AndSlot0() {
        var s = new GameSlotAllocator().allocate(List.of());
        assertEquals(2, s.displayNum());
        assertEquals(9002, s.port());
        assertEquals(0, s.loopbackSlot());
    }

    @Test
    void reusesGapsInsteadOfAppending() {
        var s = new GameSlotAllocator().allocate(List.of(game(2, 0), game(4, 2)));
        assertEquals(3, s.displayNum());
        assertEquals(9003, s.port());
        assertEquals(1, s.loopbackSlot());
    }

    @Test
    void skipsDisplayWhosePortIsTakenByAnotherGame() {
        Game odd = game(2, 0);
        odd.setPort(9003);              // 数据不一致：port 占用了 display 3 的位置
        var s = new GameSlotAllocator().allocate(List.of(odd));
        assertEquals(4, s.displayNum());
        assertEquals(9004, s.port());
    }

    @Test
    void throwsWhenAllDisplaysUsed() {
        List<Game> full = new java.util.ArrayList<>();
        for (int d = GameSlotAllocator.MIN_DISPLAY; d <= GameSlotAllocator.MAX_DISPLAY; d++) {
            full.add(game(d, d));
        }
        assertThrows(IllegalStateException.class, () -> new GameSlotAllocator().allocate(full));
    }

    @Test
    void 单卡通道用满后换到下一张卡() {
        List<Game> existing = new java.util.ArrayList<>();
        for (int s = 0; s < GameSlotAllocator.SLOTS_PER_CARD; s++) {
            existing.add(game(2 + s, 0, s));
        }
        var next = new GameSlotAllocator().allocate(existing);
        assertEquals(1, next.loopbackCard(), "card 0 满了应换到 card 1");
        assertEquals(0, next.loopbackSlot(), "新卡从 slot 0 开始");
    }

    @Test
    void 老配置没有卡号按card0处理() {
        Game legacy = new Game();
        legacy.setDisplayNum(2);
        legacy.setPort(9002);
        legacy.setLoopbackSlot(0);   // loopbackCard 默认 0
        var next = new GameSlotAllocator().allocate(List.of(legacy));
        assertEquals(0, next.loopbackCard());
        assertEquals(1, next.loopbackSlot());
    }

    @Test
    void 所有卡的通道都用满才报错() {
        List<Game> full = new java.util.ArrayList<>();
        int display = GameSlotAllocator.MIN_DISPLAY;
        for (int card = 0; card < GameSlotAllocator.LOOPBACK_CARDS; card++) {
            for (int slot = 0; slot < GameSlotAllocator.SLOTS_PER_CARD; slot++) {
                full.add(game(display++, card, slot));
            }
        }
        assertThrows(IllegalStateException.class, () -> new GameSlotAllocator().allocate(full));
    }
}
