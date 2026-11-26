package kr.hojun.policymatch.normalize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** 원본 글 다듬기 (12/10). 엔티티 값은 K-Startup 실측에 나온 것이다. */
class TextCleanerTest {

    @Test
    void K_Startup에_나온_엔티티를_푼다() {
        assertEquals("기술개발(R&D)", TextCleaner.clean("기술개발(R&amp;D)"));
        assertEquals("(예비)창업자", TextCleaner.clean("&#40;예비&#41;창업자"));
    }

    @Test
    void 여러_엔티티와_16진수도_푼다() {
        assertEquals("<b> \"A\" 'B' C", TextCleaner.clean("&lt;b&gt; &quot;A&quot; &apos;B&apos; &#x43;"));
    }

    @Test
    void 한_번만_푼다() {
        // &amp;lt; 는 원래 "&lt;" 라는 글자다. 두 번 풀어 "<" 로 만들지 않는다
        assertEquals("&lt;", TextCleaner.clean("&amp;lt;"));
    }

    @Test
    void 모르는_엔티티와_그냥_앰퍼샌드는_그대로_둔다() {
        assertEquals("A & B &foo;", TextCleaner.clean("A & B &foo;"));
    }

    @Test
    void 줄바꿈을_통일하고_앞뒤_공백을_지운다() {
        assertEquals("첫 줄\n둘째 줄\n셋째 줄", TextCleaner.clean("  첫 줄\r\n둘째 줄\r셋째 줄\n  "));
    }

    @Test
    void 비었으면_null() {
        // 빈 글이 '확인 필요' 뱃지를 띄우지 않게 한다
        assertNull(TextCleaner.clean(null));
        assertNull(TextCleaner.clean(" \r\n "));
    }
}
