/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.service;

import io.github.sander_i.domain.Tag;
import io.github.sander_i.repo.GameTagRepository;
import io.github.sander_i.repo.TagRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TagsAndMetaCreateTest {

    private TagRepository tags;
    private TagsAndMeta meta;

    @BeforeEach
    void setUp() {
        tags = mock(TagRepository.class);
        meta = new TagsAndMeta(tags, mock(GameTagRepository.class));
        when(tags.save(any(Tag.class))).thenAnswer(inv -> {
            Tag t = inv.getArgument(0);
            if (t.getId() == null) t.setId(1L);
            return t;
        });
    }

    @Test
    void createsTagWithNameAndAlias() {
        when(tags.findByName("纯爱")).thenReturn(Optional.empty());

        Tag t = meta.create(" 纯爱 ", " Junai ");

        assertEquals("纯爱", t.getName());
        assertEquals("Junai", t.getAlias());
    }

    @Test
    void rejectsBlankName() {
        assertThrows(IllegalArgumentException.class, () -> meta.create("  ", "x"));
    }

    @Test
    void returnsExistingTagWithoutOverwritingAlias() {
        Tag existing = new Tag();
        existing.setId(3L);
        existing.setName("纯爱");
        existing.setAlias("old");
        when(tags.findByName("纯爱")).thenReturn(Optional.of(existing));

        Tag t = meta.create("纯爱", "new");

        assertSame(existing, t);
        assertEquals("old", t.getAlias());
        verify(tags, never()).save(any(Tag.class));
    }

    @Test
    void reorderAssignsSequentialOrder() {
        Tag a = new Tag();
        a.setId(1L);
        a.setName("A");
        Tag b = new Tag();
        b.setId(2L);
        b.setName("B");
        when(tags.findAll()).thenReturn(java.util.List.of(a, b));

        meta.reorder(java.util.List.of(2L, 1L));

        assertEquals(1, b.getSortOrder());
        assertEquals(2, a.getSortOrder());
        verify(tags).saveAll(java.util.List.of(b, a));
    }
}
