package kr.hojun.policymatch.region;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 시도 이름 ↔ 시도 코드 (D-029 ⑥). 설정(SidoProperties)을 찾기 쉬운 형태로 바꿔 들고 있는다.
 *
 *  - 이름을 비교할 때 앞뒤 공백과 가운데 공백을 무시한다("전남 광주" = "전남광주").
 *  - "전국"은 설정에 있는 모든 시도 코드로 펼친다(K-Startup 공고의 59%, 12/5).
 *  - 설정이 잘못되면(같은 이름이 두 시도에 들어감, 코드가 2자리가 아님) 앱이 뜰 때 바로 실패한다.
 *    잘못된 매핑으로 공고가 엉뚱한 시도에 조용히 나오는 것보다 배포가 실패하는 편이 안전하다.
 */
@Component
public class SidoNames {

    private final Map<String, String> codeByName;
    private final Map<String, String> displayNameByCode;
    private final Set<String> nationwideNames;

    public SidoNames(SidoProperties props) {
        if (props == null || props.regions() == null || props.regions().isEmpty()) {
            throw new IllegalStateException("sido.regions 가 비어 있습니다.");
        }
        Map<String, String> byName = new HashMap<>();
        Map<String, String> display = new LinkedHashMap<>();
        for (SidoProperties.Region region : props.regions()) {
            String code = region.code();
            if (code == null || !code.matches("\\d{2}")) {
                throw new IllegalStateException("시도 코드는 두 자리 숫자여야 합니다: " + code);
            }
            if (region.names() == null || region.names().isEmpty()) {
                throw new IllegalStateException("시도 " + code + " 의 이름이 없습니다.");
            }
            if (display.put(code, region.names().get(0)) != null) {
                throw new IllegalStateException("시도 코드가 중복됐습니다: " + code);
            }
            for (String name : region.names()) {
                String previous = byName.put(key(name), code);
                if (previous != null && !previous.equals(code)) {
                    throw new IllegalStateException("시도 이름 '" + name + "' 이(가) 두 시도에 들어 있습니다: "
                            + previous + ", " + code);
                }
            }
        }
        Set<String> nationwide = new LinkedHashSet<>();
        for (String name : props.nationwide() == null ? List.<String>of() : props.nationwide()) {
            if (byName.containsKey(key(name))) {
                throw new IllegalStateException("'" + name + "' 이(가) 전국과 시도 이름에 모두 들어 있습니다.");
            }
            nationwide.add(key(name));
        }
        this.codeByName = Map.copyOf(byName);
        this.displayNameByCode = display;
        this.nationwideNames = Set.copyOf(nationwide);
    }

    /**
     * 이름 하나를 시도 코드로 바꾼다. 전국이면 모든 시도, 모르는 이름이면 빈 집합.
     * 모르는 이름은 정규화 로그에 남겨, 새 표기가 생겼는지 알아챈다.
     */
    public Set<String> codesOf(String name) {
        if (name == null || name.isBlank()) {
            return Set.of();
        }
        String k = key(name);
        if (nationwideNames.contains(k)) {
            return new LinkedHashSet<>(displayNameByCode.keySet());
        }
        String code = codeByName.get(k);
        return code == null ? Set.of() : Set.of(code);
    }

    /** 화면에 보일 시도 이름 (설정의 첫 이름). */
    public Optional<String> displayName(String code) {
        return Optional.ofNullable(displayNameByCode.get(code));
    }

    /** 설정에 있는 모든 시도 코드 (설정 순서). */
    public List<String> allCodes() {
        return new ArrayList<>(displayNameByCode.keySet());
    }

    static String key(String name) {
        return name == null ? "" : name.replaceAll("\\s", "");
    }
}
