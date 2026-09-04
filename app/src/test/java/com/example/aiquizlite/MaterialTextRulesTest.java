package com.example.aiquizlite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class MaterialTextRulesTest {
    @Test
    public void detectsNumberedChaptersAndChunksWithoutLosingTheirSourceLabel() {
        String text = "第一章 防火墙基础\n接口必须加入安全区域。\n"
                + "2. 安全策略\n安全策略包含源区域和目的区域。\n更多说明。";

        List<MaterialTextRules.Chapter> chapters = MaterialTextRules.detectChapters(text);
        List<MaterialTextRules.TextChunk> chunks = MaterialTextRules.chunk(chapters, 18);

        assertEquals(2, chapters.size());
        assertEquals("第一章 防火墙基础", chapters.get(0).getTitle());
        assertEquals("2. 安全策略", chapters.get(1).getTitle());
        assertTrue(chunks.size() >= 2);
        assertEquals("第一章 防火墙基础", chunks.get(0).getChapterTitle());
        assertEquals("2. 安全策略", chunks.get(chunks.size() - 1).getChapterTitle());
    }

    @Test
    public void questionBankChunksOverlapToProtectBoundaryQuestions() {
        List<MaterialTextRules.Chapter> chapters = java.util.Collections.singletonList(
                new MaterialTextRules.Chapter("题库", "1234567890abcdefghij")
        );

        List<MaterialTextRules.TextChunk> chunks =
                MaterialTextRules.chunkWithOverlap(chapters, 10, 3);

        assertEquals(2, chunks.size());
        assertTrue(chunks.get(0).getText().endsWith("abc"));
        assertTrue(chunks.get(1).getText().startsWith("890"));
    }
}
