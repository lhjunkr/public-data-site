package kr.hojun.policymatch.normalize;

import kr.hojun.policymatch.collect.Source;

/**
 * 대량 변경 감지 (D-019 "12/8 추가").
 *
 * 정규화가 한 번에 비활성하려는 정책이 상한을 넘으면 반영하지 않고 멈춘다. 상한은 출처마다 따로 잰다.
 *  - 상한 = max(최소 50건, 이번 정규화 전 활성 건수의 10%). 온통청년 3,994건이면 399건이다.
 *  - 평소 하루 비활성은 최대 26건이다(11/25 운영 실측: 지금 비활성인 58건을 꺼진 날로 묶으면 최대가 11/20 의 26건).
 *    상한 399건은 그 15배라, 넘으면 정책이 실제로 사라졌다기보다 데이터가 깨졌을 가능성이 크다고 본다.
 *    예: API 가 전체 건수까지 줄여서 일부만 줌(수집 95% 규칙을 통과함) · 승인 코드가 바뀜 · 새 수집기의 버그.
 *  - 최소 50건은 활성 건수가 작은 출처에서 평소 몇 건 빠지는 것까지 멈추지 않게 하는 바닥이다.
 *
 * 멈춘 뒤에는 사람이 원본 사이트에서 정책이 실제로 사라졌는지 확인하고, 맞으면 관리 API 로 강제 반영한다
 * ({@code POST /api/v1/admin/normalize?force=true}). 안전장치에 풀 방법이 없으면 진짜 큰 변경(연말 일괄 종료 등)
 * 때 매일 갱신이 계속 멈추기 때문이다.
 *
 * 상한 아래에서 일어나는 부분 손실(예: 분류 하나가 통째로 빠짐 = 5% 안팎)은 여기서 잡지 못한다.
 */
public final class MassDeactivationGuard {

    /** 활성 건수가 작을 때의 바닥. */
    static final int MIN_LIMIT = 50;

    /** 이번 정규화 전 활성 건수의 몇 %까지 비활성을 허용하는가. */
    static final int MAX_PERCENT = 10;

    private MassDeactivationGuard() {}

    /** 이번 정규화에서 비활성해도 되는 최대 건수. 정수 나눗셈이라 소수점 아래는 버린다. */
    static int limitFor(int activeBefore) {
        return Math.max(MIN_LIMIT, activeBefore * MAX_PERCENT / 100);
    }

    /** 비활성 예정 건수가 상한을 넘으면 MassDeactivationException 을 던진다. 상한과 같으면 통과한다. */
    static void check(Source source, int activeBefore, int toDeactivate) {
        int limit = limitFor(activeBefore);
        if (toDeactivate > limit) {
            throw new MassDeactivationException(source, activeBefore, toDeactivate, limit);
        }
    }
}
