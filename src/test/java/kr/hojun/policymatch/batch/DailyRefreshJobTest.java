package kr.hojun.policymatch.batch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.Supplier;
import kr.hojun.policymatch.alert.FakeAlertSender;
import kr.hojun.policymatch.collect.CollectResult;
import kr.hojun.policymatch.collect.ExternalApiException;
import kr.hojun.policymatch.collect.PolicyCollector;
import kr.hojun.policymatch.collect.Source;
import kr.hojun.policymatch.collect.YouthApiException;
import kr.hojun.policymatch.normalize.MassDeactivationException;
import kr.hojun.policymatch.normalize.NormalizeResult;
import org.junit.jupiter.api.Test;

/**
 * 매일 갱신이 언제 알림을 보내는가 (D-026).
 * 진짜 수집 · 정규화 대신 정해진 대로 성공하거나 실패하는 가짜 PolicyBatch, 진짜 디스코드 대신 가짜 알림을 쓴다.
 */
class DailyRefreshJobTest {

    private static final RefreshResult OK = new RefreshResult(
            new CollectResult(3763, 38, 3763, 3763),
            new NormalizeResult(3763, 3763, 0, 0, 0, 0, 0, 0, 0),
            70);

    private final FakeAlertSender alert = new FakeAlertSender();

    private void runWith(Supplier<RefreshResult> behavior) {
        new DailyRefreshJob(new FakeBatch(behavior), alert).run();
    }

    @Test
    void 갱신이_실패하면_원인과_함께_알림을_1건_보낸다() {
        runWith(() -> {
            throw new YouthApiException("API 호출 실패 (page=31, 시도=3) HttpClientErrorException: 403");
        });

        assertEquals(1, alert.sent.size());
        assertTrue(alert.sent.get(0).contains("매일 갱신 실패"));
        assertTrue(alert.sent.get(0).contains("page=31"), "어디서 실패했는지 알려 준다");
    }

    @Test
    void 알림에_온통청년_인증키를_넣지_않는다() {
        // 연결 오류 메시지에는 요청 주소가 통째로 들어 있다 (실제 RestClient 오류 모양)
        runWith(() -> {
            throw new YouthApiException("API 호출 실패 (page=31, 시도=3) ResourceAccessException: "
                    + "I/O error on GET request for \"https://www.youthcenter.go.kr/go/ythip/getPlcy"
                    + "?apiKeyNm=SECRET-KEY-123&pageNum=31&pageSize=100&rtnType=json\": Read timed out");
        });

        assertFalse(alert.sent.get(0).contains("SECRET-KEY-123"));
        assertTrue(alert.sent.get(0).contains("apiKeyNm=***"), "가렸다는 표시는 남긴다");
    }

    @Test
    void 알림에_공공데이터포털_인증키를_넣지_않는다() {
        // 12/9: K-Startup 부터 쓰는 공공데이터포털 키(serviceKey=…). 12/16 매일 갱신에 붙기 전에 미리 막아 둔다
        runWith(() -> {
            throw new ExternalApiException(Source.KSTARTUP, "API 호출 실패 (page=2, 시도=3) ResourceAccessException: "
                    + "I/O error on GET request for \"https://apis.data.go.kr/B552735/kisedKstartupService02"
                    + "/getAnnouncementInformation02?serviceKey=SECRET%2BKEY&page=2\": Read timed out");
        });

        assertFalse(alert.sent.get(0).contains("SECRET"));
        assertTrue(alert.sent.get(0).contains("serviceKey=***"));
    }

    @Test
    void 대량_비활성으로_멈추면_건수와_강제_반영_방법을_알린다() {
        // D-019 "12/8 추가": 사람이 원본을 확인하고 정해야 하므로 일반 실패와 다른 글을 보낸다
        runWith(() -> {
            throw new MassDeactivationException(Source.YOUTH, 3994, 1997, 399);
        });

        assertEquals(1, alert.sent.size());
        String text = alert.sent.get(0);
        assertTrue(text.contains("대량 비활성 감지"));
        assertTrue(text.contains("1997") && text.contains("3994") && text.contains("50%") && text.contains("399"),
                "비활성 예정 · 활성 · 비율 · 상한");
        assertTrue(text.contains("force=true"), "확인 뒤 할 일");
        assertFalse(text.contains("매일 갱신 실패"), "일반 실패 알림과 섞이지 않는다");
    }

    @Test
    void 갱신이_성공하면_알림을_보내지_않는다() {
        runWith(() -> OK);

        assertEquals(0, alert.sent.size());
    }

    @Test
    void 다른_작업이_실행_중이라_건너뛰면_알림을_보내지_않는다() {
        runWith(() -> {
            throw new BatchBusyException("수집");
        });

        assertEquals(0, alert.sent.size());
    }

    /** 수집 · 정규화를 하지 않고 정해진 대로 성공하거나 실패한다 */
    private static class FakeBatch extends PolicyBatch {
        private final Supplier<RefreshResult> behavior;

        FakeBatch(Supplier<RefreshResult> behavior) {
            // 배치는 온통청년 수집기가 있어야 만들어진다(12/9). 이 가짜는 refresh 만 쓰므로 빈 껍데기를 넘긴다
            super(List.of(new PolicyCollector(null, null, null, null)), null);
            this.behavior = behavior;
        }

        @Override
        public RefreshResult refresh() {
            return behavior.get();
        }
    }
}
