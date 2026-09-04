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
}
