package kr.hojun.policymatch.collect;

import java.time.format.DateTimeFormatter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 화면 하단의 "정책 정보 기준 시각" (D-023).
 *
 * 마지막으로 성공한 수집의 시작 시각 = 원본에서 정책을 마지막으로 받아 온 시각이다.
 * 자동 갱신이 실패한 날(예: 11/1)에는 전날 시각이 그대로 보이므로, 사용자도 정보가 언제 것인지 알 수 있다.
 * 수집은 성공했는데 정규화가 실패한 드문 날에는 실제 화면 자료보다 늦은 시각이 보일 수 있다
 * (정규화는 실패하면 통째로 되돌리므로 화면 자료는 그 이전 것). 정규화 실패는 D-019 로 드물게 만들었다.
 */
@RestController
@RequestMapping("/api/v1/data-status")
public class DataStatusController {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final CollectRunStore runs;

    public DataStatusController(CollectRunStore runs) {
        this.runs = runs;
    }

    @GetMapping
    public DataStatusResponse status() {
        return new DataStatusResponse(runs.lastSuccessStartedAt().map(FORMAT::format).orElse(null));
    }
}
