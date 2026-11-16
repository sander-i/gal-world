package io.github.sander_i.web;

import io.github.sander_i.domain.Game;
import io.github.sander_i.repo.GameRepository;
import io.github.sander_i.service.SaveService;
import io.github.sander_i.service.SessionService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SaveControllerTest {

    private Game game(String id, int port) {
        Game g = new Game();
        g.setId(id);
        g.setName(id);
        g.setPort(port);
        return g;
    }

    @Test
    void switchRejectedWhileGameRunning() throws Exception {
        SaveService saves = mock(SaveService.class);
        SessionService sessions = mock(SessionService.class);
        GameRepository repo = mock(GameRepository.class);
        when(repo.existsById("demo")).thenReturn(true);
        when(repo.findById("demo")).thenReturn(Optional.of(game("demo", 9001)));
        when(sessions.status("demo", 9001)).thenReturn(new SessionService.SessionStatus(
                "demo", SessionService.State.RUNNING, "active", null, false));

        var controller = new SaveController(saves, sessions, repo);
        var res = controller.switchTo("demo", "20260101-010101_a.tar.gz", false);

        assertEquals(409, res.getStatusCode().value());
        verifyNoInteractions(saves);
    }

    @Test
    void switchCallsRestoreWhenStopped() throws Exception {
        SaveService saves = mock(SaveService.class);
        SessionService sessions = mock(SessionService.class);
        GameRepository repo = mock(GameRepository.class);
        when(repo.existsById("demo")).thenReturn(true);
        when(repo.findById("demo")).thenReturn(Optional.of(game("demo", 9001)));
        when(sessions.status("demo", 9001)).thenReturn(new SessionService.SessionStatus(
                "demo", SessionService.State.STOPPED, "inactive", null, false));
        var archive = new SaveService.SaveArchive("20260101-010101_a.tar.gz", "a", "desc",
                SaveService.KIND_MANUAL, 10, java.time.Instant.EPOCH);
        when(saves.restore("demo", archive.name(), false)).thenReturn(archive);
        when(saves.list("demo")).thenReturn(List.of(archive));
        // state() 会拼存档目录展示名，需要游戏目录与生效目录名
        when(saves.gameDirOf("demo")).thenReturn(java.nio.file.Path.of("/tmp/demo"));
        when(saves.saveDirs("demo")).thenReturn(List.of());
        when(saves.saveDirNames("demo")).thenReturn(List.of());
        when(saves.currentArchive("demo")).thenReturn("");

        var controller = new SaveController(saves, sessions, repo);
        var res = controller.switchTo("demo", archive.name(), false);

        assertEquals(200, res.getStatusCode().value());
        verify(saves).restore("demo", archive.name(), false);
    }
}
