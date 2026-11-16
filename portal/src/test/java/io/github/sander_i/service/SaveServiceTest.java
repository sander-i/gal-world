package io.github.sander_i.service;

import io.github.sander_i.config.PathConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SaveServiceTest {

    @TempDir Path root;
    SaveService saves;

    @BeforeEach
    void setUp() throws Exception {
        saves = new SaveService(new PathConfig(root.toString()), null);
        Path savedata = root.resolve("games/g1/savedata");
        Files.createDirectories(savedata);
        Files.writeString(savedata.resolve("global.sav"), "slot-1");
    }

    @Test
    void backupStoresLabelDescriptionAndKind() throws Exception {
        saves.backup("g1", "第一章 结束", "全 CG 收集", SaveService.KIND_MANUAL);

        List<SaveService.SaveArchive> list = saves.list("g1");
        assertEquals(1, list.size());
        SaveService.SaveArchive a = list.get(0);
        assertEquals("第一章 结束", a.label());
        assertEquals("全 CG 收集", a.description());
        assertEquals(SaveService.KIND_MANUAL, a.kind());
        assertTrue(a.sizeBytes() > 0);
        // 文件名仍然只保留净化后的标签，磁盘上可读
        assertTrue(a.name().contains("_第一章-结束.tar.gz"), a.name());
    }

    @Test
    void originalArchiveIsRecognizedAndNotDuplicated() throws Exception {
        saves.backup("g1", "原始存档", "导入时留存", SaveService.KIND_ORIGINAL);

        assertTrue(saves.hasOriginal("g1"));
        assertEquals(SaveService.KIND_ORIGINAL, saves.list("g1").get(0).kind());
    }

    @Test
    void legacyArchiveWithoutMetadataFallsBackToFilename() throws Exception {
        Path dir = saves.backupRoot("g1");
        Files.createDirectories(dir);
        Files.write(dir.resolve("20260101-010101_旧备份.tar.gz"), new byte[]{1, 2, 3});

        SaveService.SaveArchive a = saves.list("g1").get(0);
        assertEquals("旧备份", a.label());
        assertEquals("", a.description());
        assertEquals(SaveService.KIND_MANUAL, a.kind());
        assertEquals(3, a.sizeBytes());
    }

    @Test
    void updateMetaKeepsKindAndCreatedAt() throws Exception {
        Path archive = saves.backup("g1", "旧标签", "旧描述", SaveService.KIND_MANUAL);
        SaveService.SaveArchive before = saves.list("g1").get(0);

        SaveService.SaveArchive updated = saves.updateMeta("g1", archive.getFileName().toString(),
                "新标签", "新描述");

        assertEquals("新标签", updated.label());
        assertEquals("新描述", updated.description());
        assertEquals(before.kind(), updated.kind());
        assertEquals(before.createdAt(), updated.createdAt());
        assertEquals("新标签", saves.list("g1").get(0).label());
    }

    @Test
    void deleteRemovesArchiveAndMetadata() throws Exception {
        // 先留一份原始存档，否则最后一份备份不允许删（见下一个用例）
        saves.backup("g1", "原始存档", "", SaveService.KIND_ORIGINAL);
        Path archive = saves.backup("g1", "t", "d", SaveService.KIND_MANUAL);
        Path meta = archive.resolveSibling(archive.getFileName() + ".json");
        assertTrue(Files.exists(archive));
        assertTrue(Files.exists(meta));

        saves.delete("g1", archive.getFileName().toString());

        assertFalse(Files.exists(archive));
        assertFalse(Files.exists(meta));
        assertEquals(1, saves.list("g1").size());
    }

    @Test
    void refusesToDeleteLastBackupWhenNoOriginal() throws Exception {
        Path only = saves.backup("g1", "仅此一份", "", SaveService.KIND_MANUAL);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> saves.delete("g1", only.getFileName().toString()));
        assertTrue(e.getMessage().contains("至少要保留一份"), e.getMessage());
        // 拒绝时不能留下副作用
        assertTrue(Files.exists(only));
        assertEquals(1, saves.list("g1").size());
    }

    @Test
    void refusesToDeleteCurrentArchive() throws Exception {
        saves.backup("g1", "原始存档", "", SaveService.KIND_ORIGINAL);
        Path target = saves.backup("g1", "要玩的", "", SaveService.KIND_MANUAL);
        saves.restore("g1", target.getFileName().toString(), false);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> saves.delete("g1", target.getFileName().toString()));
        assertTrue(e.getMessage().contains("当前正在游玩"), e.getMessage());
    }

    @Test
    void backupWithoutSaveDirsFails() {
        assertThrows(IllegalStateException.class,
                () -> saves.backup("no-save", "x", "y", SaveService.KIND_MANUAL));
    }

    @Test
    void restoreSwitchesSaveContentAndKeepsPreRestoreSnapshot() throws Exception {
        Path archive = saves.backup("g1", "old", "", SaveService.KIND_MANUAL);
        Path savedata = root.resolve("games/g1/savedata");
        Files.writeString(savedata.resolve("global.sav"), "slot-2");

        saves.restore("g1", archive.getFileName().toString());

        assertEquals("slot-1", Files.readString(savedata.resolve("global.sav")));
        // 切换前的进度必须留下来，否则切走再切回来就找不回来了
        SaveService.SaveArchive pre = saves.list("g1").stream()
                .filter(a -> SaveService.KIND_PRE_RESTORE.equals(a.kind()))
                .findFirst().orElseThrow();
        assertTrue(pre.description().contains("切换存档时自动保存"));
    }

    @Test
    void repeatedRestoreKeepsOnlyLatestPreRestoreSnapshot() throws Exception {
        Path archive = saves.backup("g1", "old", "", SaveService.KIND_MANUAL);
        Path savedata = root.resolve("games/g1/savedata");

        for (int i = 0; i < 3; i++) {
            Files.writeString(savedata.resolve("global.sav"), "slot-" + (i + 2));
            saves.restore("g1", archive.getFileName().toString());
        }

        long preCount = saves.list("g1").stream()
                .filter(a -> SaveService.KIND_PRE_RESTORE.equals(a.kind()))
                .count();
        assertEquals(1, preCount, "pre-restore 快照只保留最新一份，避免越切越堆");
    }

    @Test
    void canSwitchBackToPreRestoreSnapshot() throws Exception {
        Path archive = saves.backup("g1", "old", "", SaveService.KIND_MANUAL);
        Path savedata = root.resolve("games/g1/savedata");
        Files.writeString(savedata.resolve("global.sav"), "slot-2");

        // 第一次切换会生成「切换前进度」快照（内容 slot-2）
        saves.restore("g1", archive.getFileName().toString());
        SaveService.SaveArchive pre = saves.list("g1").stream()
                .filter(a -> SaveService.KIND_PRE_RESTORE.equals(a.kind()))
                .findFirst().orElseThrow();

        // 再切到别处，然后切回那份快照：
        // 切换目标本身是 pre-restore，清理逻辑必须避开它，否则刚拍完就又删了
        Files.writeString(savedata.resolve("global.sav"), "slot-3");
        saves.restore("g1", pre.name());

        assertEquals("slot-2", Files.readString(savedata.resolve("global.sav")));
    }

    @Test
    void rejectsPathTraversalNames() {
        assertThrows(IllegalArgumentException.class,
                () -> saves.delete("g1", "../escape.tar.gz"));
    }
}
