package kr.hojun.policymatch.batch;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import kr.hojun.policymatch.collect.CollectResult;
import kr.hojun.policymatch.collect.Source;
import kr.hojun.policymatch.collect.SourceCollector;
import kr.hojun.policymatch.collect.UnknownSourceException;
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
 *
 * 수집기는 출처별로 여러 개다(12/9~, D-029). 스프링이 SourceCollector 구현 빈을 모두 넘겨 주고, 여기서 출처로 고른다.
 * 잠금은 출처와 상관없이 하나다. 출처 수집은 몇 초면 끝나고, 출처별 실행 시각은 12/16 에 따로 정한다.
 */
@Service
public class PolicyBatch {

    private static final Logger log = LoggerFactory.getLogger(PolicyBatch.class);

    private final Map<Source, SourceCollector> collectors;
    private final PolicyNormalizer normalizer;

    /** 지금 실행 중인 작업 이름. null 이면 아무것도 돌지 않는다. */
    private final AtomicReference<String> running = new AtomicReference<>();

    /**
     * 출처 하나에 수집기는 하나여야 하고, 매일 갱신이 쓰는 온통청년 수집기는 반드시 있어야 한다.
     * 어긋나면 앱이 뜨는 순간 실패시킨다. 실행 중에 엉뚱한 수집기가 불리는 것보다 낫다.
     */
    public PolicyBatch(List<SourceCollector> collectors, PolicyNormalizer normalizer) {
        Map<Source, SourceCollector> bySource = new EnumMap<>(Source.class);
        for (SourceCollector c : collectors) {
            if (bySource.put(c.source(), c) != null) {
                throw new IllegalStateException("출처 " + c.source().code() + " 의 수집기가 둘 이상입니다.");
            }
        }
        if (!bySource.containsKey(Source.YOUTH)) {
            throw new IllegalStateException("온통청년(youth) 수집기가 없습니다. 매일 갱신을 할 수 없습니다.");
        }
        this.collectors = Collections.unmodifiableMap(bySource);
        this.normalizer = normalizer;
    }

    /** 온통청년 수집. 관리 API 의 /collect 기본값이다. */
    public CollectResult collect() {
        return collect(Source.YOUTH);
    }

    /** 출처 하나의 원본만 수집한다. 정규화는 하지 않는다. */
    public CollectResult collect(Source source) {
        SourceCollector collector = collectors.get(source);
        if (collector == null) {
            throw new UnknownSourceException("출처 " + source.code() + " 의 수집기는 아직 없습니다.");
        }
        String name = source == Source.YOUTH ? "수집" : "수집(" + source.code() + ")";
        return exclusive(name, collector::collectAll);
    }

    public NormalizeResult normalize() {
        return normalize(false);
    }

    /**
     * @param allowMassDeactivation true 면 대량 비활성 상한을 보지 않는다(D-019 "12/8 추가").
     *        사람이 원본을 확인한 뒤 관리 API 로만 켠다. 수집+정규화(refresh)에는 이 선택지를 두지 않는다 —
     *        방금 받은, 아무도 확인하지 않은 데이터를 상한 없이 반영하게 되기 때문이다.
     */
    public NormalizeResult normalize(boolean allowMassDeactivation) {
        if (allowMassDeactivation) {
            return exclusive("정규화(강제)", normalizer::normalizeAllowingMassDeactivation);
        }
        return exclusive("정규화", normalizer::normalize);
    }

    /**
     * 온통청년 수집 → 성공했을 때만 정규화. 새 출처는 12/16 수집 스케줄 통합 때 붙인다.
     * 수집이 실패하면 예외가 그대로 나가고 정규화는 돌지 않는다. 화면은 마지막 성공 데이터를 계속 보여 준다(D-017).
     */
    public RefreshResult refresh() {
        return exclusive("수집+정규화", () -> {
            long startedMs = System.currentTimeMillis();
            CollectResult collected = collectors.get(Source.YOUTH).collectAll();
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
