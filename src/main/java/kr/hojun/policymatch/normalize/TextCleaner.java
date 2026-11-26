package kr.hojun.policymatch.normalize;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 원본 글을 화면에 그대로 보여 줄 수 있게 다듬는다 (12/10).
 *
 *  - HTML 엔티티를 글자로 푼다. K-Startup 은 JSON 안에 `&amp;` · `&#40;` · `&#41;` 를 그대로 담아 보낸다(12/5 · 12/10 측정).
 *    풀지 않으면 화면에 "기술개발(R&amp;D)"처럼 보인다. 한 번만 푼다(`&amp;lt;` → `&lt;`, 두 번 풀지 않음).
 *  - 줄바꿈을 \n 하나로 통일한다(원본에 \r\n 이 섞여 있다).
 *  - 앞뒤 공백을 지우고, 비었으면 null 로 둔다. 빈 글이 "확인 필요" 뱃지를 띄우지 않게 하기 위해서다.
 */
public final class TextCleaner {

    private static final Pattern ENTITY = Pattern.compile("&(#\\d{1,7}|#[xX][0-9a-fA-F]{1,6}|amp|lt|gt|quot|apos|nbsp);");
    private static final Map<String, String> NAMED = Map.of(
            "amp", "&", "lt", "<", "gt", ">", "quot", "\"", "apos", "'", "nbsp", " ");

    private TextCleaner() {
    }

    public static String clean(String text) {
        if (text == null) {
            return null;
        }
        String out = decodeEntities(text.replace("\r\n", "\n").replace('\r', '\n')).strip();
        return out.isEmpty() ? null : out;
    }

    static String decodeEntities(String text) {
        if (text.indexOf('&') < 0) {
            return text;
        }
        Matcher m = ENTITY.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(decodeOne(m.group(1), m.group())));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String decodeOne(String name, String original) {
        String named = NAMED.get(name);
        if (named != null) {
            return named;
        }
        try {
            int codePoint = name.charAt(1) == 'x' || name.charAt(1) == 'X'
                    ? Integer.parseInt(name.substring(2), 16)
                    : Integer.parseInt(name.substring(1));
            return Character.isValidCodePoint(codePoint) ? new String(Character.toChars(codePoint)) : original;
        } catch (NumberFormatException e) {
            return original;
        }
    }
}
