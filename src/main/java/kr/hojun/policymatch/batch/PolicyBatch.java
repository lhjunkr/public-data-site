package kr.hojun.policymatch.batch;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import kr.hojun.policymatch.collect.CollectResult;
import kr.hojun.policymatch.collect.PolicyCollector;
import kr.hojun.policymatch.normalize.NormalizeResult;
import kr.hojun.policymatch.normalize.PolicyNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 수집 · 정규화를 실행하는 단 하나의 입구 (D-020).
 *
 * 관리용 엔드포인트와 매일 자동 실행이 모두 이 클래스를 거친다. 그래서 작업이 동시에 두 개 돌지 않는다.
 *  - 수집이 겹치면 외부 API 를 두 배로 부른다 (인증키 쿼터 소모).
 *  - 정규화가 겹치면 뒤의 것이 앞의 것의 행 잠금을 기다린다. 정규화 한 번이 약 70초 걸려
 *    MySQL 잠금 대기 한도(기본 50초)를 넘으므로 뒤의 것이 실패한다.
 *
 * 잠금은 앱 안의 변수 하나다. 앱이 한 대뿐이라 이것으로 충분하다.
 * 앱이 두 대 이상이 되면 DB 잠금(MySQL GET_LOCK 등)으로 바꿔야 한다.
 *
 * 잠금은 트랜잭션 바깥에서 잡고 푼다. 정규화의 트랜잭션은 normalizer.normalize() 안에서 시작해 커밋까지 끝나므로,
 * 잠금이 풀리는 시점에는 이미 커밋되어 있다. 안에서 풀면 커밋 전에 다음 작업이 시작될 수 있다.
 */
@Service
public class PolicyBatch {

    private static final Logger log = LoggerFactory.getLogger(PolicyBatch.class);

    private final PolicyCollector collector;
    private final PolicyNormalizer normalizer;

    /** 지금 실행 중인 작업 이름. null 이면 아무것도 돌지 않는다. */
    private final AtomicReference<String> running = new AtomicReference<>();

    public PolicyBatch(PolicyCollector collector, PolicyNormalizer normalizer) {
        this.collector = collector;
        this.normalizer = normalizer;
    }

    public CollectResult collect() {
        return exclusive("수집", collector::collectAll);
    }

    public NormalizeResult normalize() {
        return exclusive("정규화", normalizer::normalize);
    }

    /**
     * 수집 → 성공했을 때만 정규화.
     * 수집이 실패하면 예외가 그대로 나가고 정규화는 돌지 않는다. 화면은 마지막 성공 데이터를 계속 보여 준다(D-017).
     */
    public RefreshResult refresh() {
        return exclusive("수집+정규화", () -> {
            long startedMs = System.currentTimeMillis();
            CollectResult collected = collector.collectAll();
            NormalizeResult normalized = normalizer.normalize();
            long elapsedSeconds = (System.currentTimeMillis() - startedMs) / 1000;
            return new RefreshResult(collected, normalized, elapsedSeconds);
        });
    }

    private <T> T exclusive(String name, Supplier<T> work) {
        // 비어 있을 때만 내 이름을 넣는다. 확인과 넣기가 한 번에 일어나므로 두 요청이 동시에 와도 하나만 통과한다.
        if (!running.compareAndSet(null, name)) {
            throw new BatchBusyException(running.get());
        }
        log.info("{} 시작", name);
        try {
            return work.get();
        } finally {
            running.set(null);
        }
    }
}
