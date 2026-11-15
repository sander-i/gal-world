/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package com.galworld.service;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FaviconServiceTest {

    private final URI base = URI.create("https://example.com/games/index.html");

    @Test
    void extractsIconLinksInPriorityOrder() {
        String html = """
                <html><head>
                  <link rel="stylesheet" href="/a.css">
                  <link rel="icon" href="/favicon-32.png">
                  <link rel="apple-touch-icon" href="https://cdn.example.com/touch.png">
                  <link rel="shortcut icon" href="rel/icon.ico">
                </head></html>
                """;

        List<URI> urls = FaviconService.iconCandidates(html, base);

        assertEquals(3, urls.size());
        assertTrue(urls.contains(URI.create("https://example.com/favicon-32.png")));
        assertTrue(urls.contains(URI.create("https://cdn.example.com/touch.png")));
        assertTrue(urls.contains(URI.create("https://example.com/games/rel/icon.ico")));
    }

    @Test
    void ignoresNonIconLinksAndResolvesRelativePaths() {
        String html = "<link rel=\"stylesheet\" href=\"/x.css\"><link rel=icon href='sub/i.png'>";

        assertEquals(List.of(URI.create("https://example.com/games/sub/i.png")),
                FaviconService.iconCandidates(html, base));
    }

    @Test
    void noIconLinksReturnsEmptyList() {
        assertEquals(List.of(), FaviconService.iconCandidates("<html></html>", base));
    }
}
