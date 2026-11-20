/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.web;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 串流桥接队列的丢帧策略。
 *
 * 背景：门户 → 浏览器方向的队列曾经是无界的。快速跳过对话时浏览器消化不过来，
 * 队列无限涨，延迟越拖越长；而 Selkies 因为本地 socket 一直被读空，它自己的
 * 字节预算丢帧永远不会触发。现在改成有界，超预算丢最旧的视频帧，让客户端的
 * LOST_FRAME / REQUEST_KEYFRAME 路径去自愈。
 */
class StreamWsBridgeQueueTest {

    /** 造一帧 Selkies 视频分片：首字节 0x04，长度至少 12 */
    private static Object[] video(int size) {
        ByteBuffer b = ByteBuffer.allocate(Math.max(12, size));
        b.put(0, (byte) 0x04);
        return new Object[]{null, b};
    }

    private static Object[] text(String s) {
        return new Object[]{s, null};
    }

    private static int firstByte(Object[] f) {
        ByteBuffer b = (ByteBuffer) f[1];
        return b.get(b.position()) & 0xFF;
    }

    @Test
    void downstreamDropsOldestVideoFramesWhenOverByteBudget() throws Exception {
        var q = new StreamSwBridgeQueueHelper().downstream();
        // 每帧 1 MiB，预算 4 MiB：第 5 帧入队时必须把最旧的挤掉
        for (int i = 0; i < 5; i++) {
            q.put(video(1 << 20));
        }
        assertEquals(4, q.size(), "4 MiB 预算下最多留 4 个 1 MiB 帧");
        assertEquals(1, q.dropped(), "第 5 帧挤掉了最旧的一帧");
    }

    @Test
    void downstreamDropsByFrameCountWhenFramesAreTiny() throws Exception {
        var q = new StreamSwBridgeQueueHelper().downstream();
        for (int i = 0; i < 200; i++) {
            q.put(video(64));
        }
        assertEquals(180, q.size(), "帧数上限 180（60fps 约 3 秒）");
        assertEquals(20, q.dropped());
    }

    @Test
    void downstreamKeepsControlFrames() throws Exception {
        var q = new StreamSwBridgeQueueHelper().downstream();
        q.put(text("SETTINGS,{}"));
        for (int i = 0; i < 200; i++) {
            q.put(video(64));
        }
        // 控制帧在队首，挤掉的是视频帧；它最终仍然按序被取到
        Object[] first = q.poll(0);
        assertNotNull(first);
        assertEquals("SETTINGS,{}", first[0], "控制帧不能被视频帧的预算挤掉");
    }

    @Test
    void downstreamPollReleasesByteBudget() throws Exception {
        var q = new StreamSwBridgeQueueHelper().downstream();
        for (int i = 0; i < 4; i++) {
            q.put(video(1 << 20));
        }
        assertEquals(4, q.size());
        assertEquals(0, q.dropped(), "正好 4 MiB，不超预算");
        // 取走两帧后应能再放两帧而不丢
        q.poll(0);
        q.poll(0);
        q.put(video(1 << 20));
        q.put(video(1 << 20));
        assertEquals(4, q.size());
        assertEquals(0, q.dropped(), "取出后字节计数必须同步扣减，否则会误丢");
    }

    @Test
    void upstreamNeverDropsControlFrames() throws Exception {
        var q = new StreamSwBridgeQueueHelper().upstream();
        for (int i = 0; i < 600; i++) {
            q.put(text("CLIENT_FRAME_ACK " + i));
        }
        // 控制帧一律保留：宁可超上限，也不能丢状态
        assertEquals(600, q.size());
        assertEquals(0, q.dropped());
    }

    @Test
    void upstreamDropsOldestBinaryWhenOverLimit() throws Exception {
        var q = new StreamSwBridgeQueueHelper().upstream();
        for (int i = 0; i < 600; i++) {
            q.put(video(64));   // 麦克风 PCM 走二进制，首字节不是 0x04 也一样
        }
        assertEquals(512, q.size());
        assertEquals(88, q.dropped());
    }

    @Test
    void upstreamPrefersDroppingBinaryOverControl() throws Exception {
        var q = new StreamSwBridgeQueueHelper().upstream();
        q.put(text("SETTINGS,{}"));
        for (int i = 0; i < 600; i++) {
            q.put(video(64));
        }
        assertEquals(512, q.size());
        Object[] first = q.poll(0);
        assertNotNull(first);
        assertEquals("SETTINGS,{}", first[0], "二进制满了也只丢二进制，控制帧保留");
    }

    @Test
    void videoDetectionMatchesSelkiesWireFormat() {
        assertTrue(new StreamSwBridgeQueueHelper().isVideo(video(12)));
        assertFalse(new StreamSwBridgeQueueHelper().isVideo(text("x")));
        assertFalse(new StreamSwBridgeQueueHelper().isVideo(new Object[]{null, ByteBuffer.allocate(11)}),
                "短于 12 字节不是视频分片");
        ByteBuffer notVideo = ByteBuffer.allocate(64);
        notVideo.put(0, (byte) 0x03);   // JPEG 分片
        assertFalse(new StreamSwBridgeQueueHelper().isVideo(new Object[]{null, notVideo}));
    }

    /** 用反射之外的正常构造路径拿到队列，避免为测试放宽字段可见性 */
    private static final class StreamSwBridgeQueueHelper {
        StreamWsBridge.FrameQueue downstream() {
            return new StreamWsBridge.FrameQueue(true);
        }

        StreamWsBridge.FrameQueue upstream() {
            return new StreamWsBridge.FrameQueue(false);
        }

        boolean isVideo(Object[] f) {
            return StreamWsBridge.FrameQueue.isVideo(f);
        }
    }
}
