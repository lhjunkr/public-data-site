package kr.hojun.policymatch.normalize;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 어떤 정책을 정규화할지 정하는 설정.
 *
 * 승인된 정책은 분류와 상관없이 모두 정규화한다(D-018). 화면에 어느 분류로 보일지, 결과에 나올지는
 * 묶음표(category.tabs, CategoryProperties)가 정한다. 건수 상한은 없다(D-014).
 * 예전에는 여기서 중분류 3개만 골랐다(D-014) — 그 역할은 묶음표의 열린 탭으로 옮겨 갔다.
 */
@ConfigurationProperties(prefix = "curation")
public class CurationProperties {

    /** 승인 상태 코드. 승인된 정책만 적재한다(D-007). */
    private String approvedStatusCode = "0044002";

    public String getApprovedStatusCode() { return approvedStatusCode; }
    public void setApprovedStatusCode(String approvedStatusCode) { this.approvedStatusCode = approvedStatusCode; }
}
