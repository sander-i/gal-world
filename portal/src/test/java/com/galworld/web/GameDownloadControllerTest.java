package com.galworld.web;

import com.galworld.domain.Game;
import com.galworld.repo.GameRepository;
import com.galworld.service.GameDownloadService;
import com.galworld.service.SessionService;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GameDownloadControllerTest {

    private Game game(String id, int port) {
        Game g = new Game();
        g.setId(id);
        g.setName(id);
        g.setPort(port);
        return g;
    }

    @Test
    void createRejectsRunningGame() {
        GameDownloadService downloads = mock(GameDownloadService.class);
        GameRepository repo = mock(GameRepository.class);
        SessionService sessions = mock(SessionService.class);
        when(repo.findById("demo")).thenReturn(Optional.of(game("demo", 9001)));
        when(sessions.status("demo", 9001)).thenReturn(new SessionService.SessionStatus(
                "demo", SessionService.State.RUNNING, "active", null, false));

        var controller = new GameDownloadController(downloads, repo, sessions);
        var res = controller.create("demo");

        assertEquals(409, res.getStatusCode().value());
        verifyNoInteractions(downloads);
    }

    @Test
    void createStartsPackagingWhenStopped() {
        GameDownloadService downloads = mock(GameDownloadService.class);
        GameRepository repo = mock(GameRepository.class);
        SessionService sessions = mock(SessionService.class);
        when(repo.findById("demo")).thenReturn(Optional.of(game("demo", 9001)));
        when(sessions.status("demo", 9001)).thenReturn(new SessionService.SessionStatus(
                "demo", SessionService.State.STOPPED, "inactive", null, false));
        var job = new GameDownloadService.JobView("dl-1", "demo", "QUEUED", "demo.zip",
                0, 0, 0, 0, "", "2026-01-01T00:00:00Z", null, null, null);
        when(downloads.start("demo")).thenReturn(job);

        var controller = new GameDownloadController(downloads, repo, sessions);
        var res = controller.create("demo");

        assertEquals(202, res.getStatusCode().value());
        assertEquals(job, res.getBody());
    }

    @Test
    void estimatePassesThroughService() throws Exception {
        GameDownloadService downloads = mock(GameDownloadService.class);
        GameRepository repo = mock(GameRepository.class);
        SessionService sessions = mock(SessionService.class);
        when(repo.findById("demo")).thenReturn(Optional.of(game("demo", 9001)));
        var est = new GameDownloadService.Estimate(1024, 3, 7);
        when(downloads.estimate("demo")).thenReturn(est);

        var controller = new GameDownloadController(downloads, repo, sessions);

        assertEquals(est, controller.estimate("demo"));
    }

    @Test
    void currentReturnsJobForRefreshRecovery() {
        GameDownloadService downloads = mock(GameDownloadService.class);
        GameRepository repo = mock(GameRepository.class);
        SessionService sessions = mock(SessionService.class);
        when(repo.findById("demo")).thenReturn(Optional.of(game("demo", 9001)));
        var job = new GameDownloadService.JobView("dl-1", "demo", "RUNNING", "demo.zip",
                100, 40, 2, 1, "", "2026-01-01T00:00:00Z", null, null, null);
        when(downloads.current("demo")).thenReturn(job);

        var controller = new GameDownloadController(downloads, repo, sessions);
        var res = controller.current("demo");

        assertEquals(200, res.getStatusCode().value());
        assertEquals(job, res.getBody());
    }

    @Test
    void currentWithoutJobIs204() {
        GameDownloadService downloads = mock(GameDownloadService.class);
        GameRepository repo = mock(GameRepository.class);
        SessionService sessions = mock(SessionService.class);
        when(repo.findById("demo")).thenReturn(Optional.of(game("demo", 9001)));
        when(downloads.current("demo")).thenReturn(null);

        var controller = new GameDownloadController(downloads, repo, sessions);
        var res = controller.current("demo");

        assertEquals(204, res.getStatusCode().value());
        assertNull(res.getBody());
    }

    @Test
    void unknownGameIs404() {
        GameDownloadService downloads = mock(GameDownloadService.class);
        GameRepository repo = mock(GameRepository.class);
        SessionService sessions = mock(SessionService.class);
        when(repo.findById("nope")).thenReturn(Optional.empty());

        var controller = new GameDownloadController(downloads, repo, sessions);
        var res = assertThrows(GameDownloadService.NotFound.class, () -> controller.create("nope"));

        assertEquals(404, controller.notFound(res).getStatusCode().value());
    }
}
