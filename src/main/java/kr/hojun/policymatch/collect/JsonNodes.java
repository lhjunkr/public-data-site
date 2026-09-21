package kr.hojun.policymatch.collect;

import tools.jackson.databind.JsonNode;

/**
 * JsonNode 에서 값을 꺼내는 최소 도우미.
 *
 * Jackson 2 → 3 으로 오면서 값 추출 메서드 이름이 여러 번 바뀌었다(asText/asString/stringValue 등).
 * 여기서는 버전이 바뀌어도 의미가 변하지 않는 toString() 표현만 사용해, 그 변화에 흔들리지 않게 한다.
 * toString() 은 JSON 표현을 돌려주므로 문자열 노드는 양쪽에 따옴표가 붙는다. 그것만 벗겨내면 된다.
 */
final class JsonNodes {

    private JsonNodes() {
    }

    static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String raw = node.toString();
        if (raw.length() >= 2 && raw.charAt(0) == '"' && raw.charAt(raw.length() - 1) == '"') {
            return raw.substring(1, raw.length() - 1);
        }
        return raw;
    }

    static int intValue(JsonNode node, int defaultValue) {
        String text = text(node);
        if (text == null || text.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
