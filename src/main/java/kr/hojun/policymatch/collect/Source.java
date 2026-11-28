package kr.hojun.policymatch.collect;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * 데이터 출처 (D-029). policy · policy_raw · collect_run 의 source 칸에 {@link #code()} 가 들어간다.
 *
 * 유니크 키가 (source, policy_no) 이므로, 같은 번호라도 출처가 다르면 다른 행이다.
 * 수집 성공 판단 · 비활성 처리도 출처별로 한다. 한 출처의 수집이 다른 출처의 정책을 비활성하지 않게 하기 위해서다.
 *
 * 새 출처는 수집기를 붙이는 날 하나씩 추가한다: kstartup(12/9) · myhome(12/11) · alio(12/13) · cleaneye(12/14).
 */
public enum Source {

    /** 온통청년 청년정책 API. V10 이전의 모든 행이 이 출처다(기본값으로 채워짐). */
    YOUTH("youth"),

    /** K-Startup 지원사업 공고 (창업진흥원, 공공데이터포털). policy_no = pbanc_sn. */
    KSTARTUP("kstartup"),

    /** 마이홈 공공주택 모집공고 (국토교통부, 공공데이터포털). policy_no = rent-{pblancId}-{houseSn} · sale-{pblancId}-{houseSn}. */
    MYHOME("myhome"),

    /** 잡알리오 공공기관 채용정보 (재정경제부, 공공데이터포털). policy_no = recrutPblntSn. */
    ALIO("alio");

    private final String code;

    Source(String code) {
        this.code = code;
    }

    /** DB 의 source 칸에 저장되는 값. */
    public String code() {
        return code;
    }

    /** 관리 API 의 ?source= 값처럼 글자로 받은 출처를 찾는다. 없으면 가능한 값을 알려 주는 예외. */
    public static Source fromCode(String code) {
        for (Source s : values()) {
            if (s.code.equals(code)) {
                return s;
            }
        }
        String all = Arrays.stream(values()).map(Source::code).collect(Collectors.joining(", "));
        throw new UnknownSourceException("모르는 출처입니다: " + code + " (가능한 값: " + all + ")");
    }
}
