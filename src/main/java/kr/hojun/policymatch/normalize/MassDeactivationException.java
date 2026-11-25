package kr.hojun.policymatch.normalize;

import kr.hojun.policymatch.collect.Source;

/**
 * 정규화가 비활성하려는 정책이 상한을 넘어 반영을 멈췄다 (D-019 "12/8 추가", {@link MassDeactivationGuard}).
 * 정규화의 트랜잭션 안에서 던지므로 이번 정규화의 모든 변경이 되돌려지고, 화면은 마지막 반영 데이터를 그대로 보여 준다.
 */
public class MassDeactivationException extends RuntimeException {

    private final Source source;
    private final int activeBefore;
    private final int toDeactivate;
    private final int limit;

    public MassDeactivationException(Source source, int activeBefore, int toDeactivate, int limit) {
        super("비활성 예정 " + toDeactivate + "건이 상한 " + limit + "건(활성 " + activeBefore + "건의 "
                + MassDeactivationGuard.MAX_PERCENT + "%, 최소 " + MassDeactivationGuard.MIN_LIMIT
                + "건)을 넘어 반영하지 않습니다. 출처: " + source.code());
        this.source = source;
        this.activeBefore = activeBefore;
        this.toDeactivate = toDeactivate;
        this.limit = limit;
    }

    public Source source() { return source; }
    public int activeBefore() { return activeBefore; }
    public int toDeactivate() { return toDeactivate; }
    public int limit() { return limit; }

    /** 활성 건수 대비 비활성 예정 비율(%). 활성이 0건이면 0. */
    public int percent() {
        return activeBefore == 0 ? 0 : toDeactivate * 100 / activeBefore;
    }
}
