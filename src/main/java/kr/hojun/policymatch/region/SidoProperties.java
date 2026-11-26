package kr.hojun.policymatch.region;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 시도 이름 → 우리 시도 코드 표 (D-029 ⑥). application.yml 의 sido.* 를 그대로 담는다.
 *
 * 같은 시도가 출처마다 다르게 적혀 온다(정식 이름 · 약칭 · 통합 전 이름). 매핑을 코드가 아니라 설정에 둬서,
 * 새 표기가 나오면 설정만 고친다(D-011과 같은 방식).
 *
 * @param regions    시도 코드와 그 시도를 가리키는 이름들. 첫 이름이 화면에 보이는 이름이다
 * @param nationwide 전국을 뜻하는 이름 (예: "전국"). 모든 시도 코드로 펼친다
 */
@ConfigurationProperties(prefix = "sido")
public record SidoProperties(List<Region> regions, List<String> nationwide) {

    public record Region(String code, List<String> names) {}
}
