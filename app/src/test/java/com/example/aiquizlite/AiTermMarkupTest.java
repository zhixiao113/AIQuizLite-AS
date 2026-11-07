package com.example.aiquizlite;

import org.junit.Test;
import static org.junit.Assert.*;

public class AiTermMarkupTest {
    @Test public void offsetsFollowStrippedMarkupIncludingRepeatedTerms() {
        AiTermMarkup.Parsed parsed = AiTermMarkup.parse("用[[ 缓存 ]]减少请求，[[缓存]]命中后直接读取。");
        assertEquals("用缓存减少请求，缓存命中后直接读取。", parsed.text);
        assertEquals(2, parsed.ranges.size());
        for (AiTermMarkup.Range range : parsed.ranges) {
            assertEquals("缓存", parsed.text.substring(range.start, range.end));
        }
        assertEquals(1, parsed.ranges.get(0).start);
        assertEquals(8, parsed.ranges.get(1).start);
    }

    @Test public void utf16OffsetsRemainAlignedAfterEmoji() {
        AiTermMarkup.Parsed parsed = AiTermMarkup.parse("🧠[[工作记忆]]与[[长期记忆]]");
        assertEquals(2, parsed.ranges.get(0).start);
        assertEquals("长期记忆", parsed.text.substring(parsed.ranges.get(1).start, parsed.ranges.get(1).end));
    }

    @Test public void legacyAndBrokenMarkupRemainReadableWithoutInventedTerms() {
        assertEquals(0, AiTermMarkup.parse("普通讲解没有术语标记").ranges.size());
        AiTermMarkup.Parsed parsed = AiTermMarkup.parse("没有闭合的[[标记与单括号[内容]");
        assertEquals("没有闭合的[[标记与单括号[内容]", parsed.text);
        assertTrue(parsed.ranges.isEmpty());
        assertEquals("", AiTermMarkup.parse(null).text);
        assertTrue(AiTermMarkup.parse("[[   ]]").ranges.isEmpty());
    }
}
