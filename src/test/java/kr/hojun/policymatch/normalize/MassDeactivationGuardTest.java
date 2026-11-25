package kr.hojun.policymatch.normalize;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import kr.hojun.policymatch.collect.Source;
import org.junit.jupiter.api.Test;

/**
 * 대량 비활성 상한의 경계값 (D-019 "12/8 추가"). DB 없이 규칙만 본다.
 * 상한 = max(50, 정규화 전 활성의 10%). 상한과 같으면 통과, 1건이라도 넘으면 멈춘다.
 */
class MassDeactivationGuardTest {

    @Test
    void 상한은_활성의_10퍼센트이고_소수점은_버린다() {
        assertEquals(399, MassDeactivationGuard.limitFor(3994), "11/25 운영 활성 3,994건 → 399건");
        assertEquals(51, MassDeactivationGuard.limitFor(510));
    }

    @Test
    void 활성이_적으면_최소_50건을_상한으로_쓴다() {
        assertEquals(50, MassDeactivationGuard.limitFor(0), "처음 붙는 출처");
        assertEquals(50, MassDeactivationGuard.limitFor(100));
        assertEquals(50, MassDeactivationGuard.limitFor(509), "10%가 50.9 → 50건이라 바닥과 같다");
    }

    @Test
    void 상한과_같으면_통과한다() {
        assertDoesNotThrow(() -> MassDeactivationGuard.check(Source.YOUTH, 3994, 399));
        assertDoesNotThrow(() -> MassDeactivationGuard.check(Source.YOUTH, 3994, 0));
    }

    @Test
    void 상한을_1건이라도_넘으면_건수를_담아_멈춘다() {
        MassDeactivationException e = assertThrows(MassDeactivationException.class,
                () -> MassDeactivationGuard.check(Source.YOUTH, 3994, 400));

        assertEquals(Source.YOUTH, e.source());
        assertEquals(3994, e.activeBefore());
        assertEquals(400, e.toDeactivate());
        assertEquals(399, e.limit());
        assertEquals(10, e.percent());
        assertTrue(e.getMessage().contains("400") && e.getMessage().contains("399"), "알림 · 로그에 건수가 보인다");
    }

    @Test
    void 사고_규모의_변경은_멈춘다() {
        // 원본이 절반만 남은 상황(예: API 가 전체 건수까지 줄여서 일부만 줌 → 수집 95% 규칙은 통과)
        assertThrows(MassDeactivationException.class, () -> MassDeactivationGuard.check(Source.YOUTH, 3994, 1997));
    }
}
