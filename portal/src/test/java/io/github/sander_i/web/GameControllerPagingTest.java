/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.web;

import io.github.sander_i.domain.Game;
import io.github.sander_i.repo.GameRepository;
import io.github.sander_i.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GameControllerPagingTest {

    private GameRepository repo;
    private GameController controller;
    private LocalizationGroupService localizations;
    private CompanyService companies;

    private Game game(String id, String name) {
        Game g = new Game();
        g.setId(id);
        g.setName(name);
        g.setPort(9000);
        return g;
    }

    @BeforeEach
    void setUp() {
        repo = mock(GameRepository.class);
        when(repo.findAllByOrderByNameAsc()).thenReturn(List.of(
                game("a", "A"), game("b", "B"), game("c", "C"),
                game("d", "D"), game("e", "E")));

        TagsAndMeta meta = mock(TagsAndMeta.class);
        when(meta.tagsByGame(anyList())).thenReturn(Map.of());
        when(meta.ofGame(anyString())).thenReturn(List.of());

        GameMediaService media = mock(GameMediaService.class);
        when(media.itemsOf(anyString(), anyString())).thenReturn(List.of());

        PlaytimeService playtime = mock(PlaytimeService.class);
        when(playtime.summary(anyString()))
                .thenReturn(new PlaytimeService.PlaytimeSummary(0, 0, 0, null));

        SessionService sessions = mock(SessionService.class);
        when(sessions.status(anyString(), anyInt()))
                .thenReturn(new SessionService.SessionStatus(
                        "g", SessionService.State.STOPPED, "stopped", null, false));

        CompanyService companies = mock(CompanyService.class);
        when(companies.ofGame(anyString())).thenReturn(List.of());
        this.companies = companies;
        LocalizationGroupService localizations = mock(LocalizationGroupService.class);
        when(localizations.ofGame(anyString())).thenReturn(List.of());
        this.localizations = localizations;

        SettingsService settings = mock(SettingsService.class);
        when(settings.coverRotationMode()).thenReturn("seq");
        when(settings.cgRotationMode()).thenReturn("autoNext");
        when(settings.cgRotationOrder()).thenReturn("seq");

        controller = new GameController(repo, mock(GameScanService.class), sessions, playtime,
                meta, companies, localizations, media, settings,
                mock(GameDeletionService.class));
    }

    @Test
    void pagesWithOffsetAndLimit() {
        var res = controller.list(null, null, null, null, 1, 2, new MockHttpServletResponse());
        assertEquals(2, res.getBody().size());
        assertEquals("b", res.getBody().get(0).id());
        assertEquals("c", res.getBody().get(1).id());
    }

    @Test
    void setsTotalCountHeader() {
        var resp = new MockHttpServletResponse();
        controller.list(null, null, null, null, 0, 2, resp);
        assertEquals("5", resp.getHeader("X-Total-Count"));
    }

    @Test
    void filtersByLocalizationGroup() {
        when(localizations.gameIdsOfId(2L)).thenReturn(java.util.Set.of("c"));

        var res = controller.list(null, null, "2", null, 0, 10, new MockHttpServletResponse());

        assertEquals(1, res.getBody().size());
        assertEquals("c", res.getBody().get(0).id());
    }

    @Test
    void filtersByCompanyId() {
        when(companies.gameIdsOfId(3L)).thenReturn(java.util.Set.of("d"));

        var res = controller.list(null, "3", null, null, 0, 10, new MockHttpServletResponse());

        assertEquals(1, res.getBody().size());
        assertEquals("d", res.getBody().get(0).id());
    }

    @Test
    void clampsLimitAndOffset() {
        var res = controller.list(null, null, null, null, -5, 0, new MockHttpServletResponse());
        assertEquals(1, res.getBody().size());
        assertEquals("a", res.getBody().get(0).id());

        var huge = controller.list(null, null, null, null, 0, 9999, new MockHttpServletResponse());
        assertEquals(5, huge.getBody().size());
    }
}
