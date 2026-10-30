package kr.hojun.policymatch.job;

import java.util.Arrays;
import java.util.Optional;

/**
 * 사용자가 고를 수 있는 취업 상태 (D-021).
 *
 * 코드와 이름은 온통청년 공식 코드표(API코드정보.xlsx 의 jobCd, "정책취업요건코드")를 그대로 옮겼다.
 * 공식 값이라 우리가 바꿀 일이 없으므로 설정 파일이 아니라 코드에 둔다 (분야 묶음표는 우리가 정한 값이라 설정 파일, D-018).
 *
 * 선언 순서 = 화면 순서. 실제 정책 수가 많고 흔한 상태를 위에 둔다 (10/30 측정: 미취업자 479 · 재직자 211 …).
 * 제한없음(0013010)은 정책 쪽에만 붙는 값이라 선택지에 넣지 않는다.
 */
public enum JobStatus {

    UNEMPLOYED("0013003", "미취업자"),
    EMPLOYED("0013001", "재직자"),
    SELF_EMPLOYED("0013002", "자영업자"),
    FOUNDER("0013006", "(예비)창업자"),
    FREELANCER("0013004", "프리랜서"),
    SHORT_TERM("0013007", "단기근로자"),
    DAILY("0013005", "일용근로자"),
    FARMER("0013008", "영농종사자"),
    OTHER("0013009", "기타");

    /** 정책이 취업 상태를 가리지 않음. 매칭 SQL 에서 사용자의 선택과 상관없이 포함시키는 값이다. */
    public static final String UNLIMITED_CODE = "0013010";

    private final String code;
    private final String label;

    JobStatus(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    public static Optional<JobStatus> ofCode(String code) {
        return Arrays.stream(values()).filter(s -> s.code.equals(code)).findFirst();
    }
}
