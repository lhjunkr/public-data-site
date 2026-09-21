package kr.hojun.policymatch.normalize;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 어떤 정책을 서비스 대상으로 삼을지 정하는 설정 (D-011: 카테고리 범위는 설정으로 관리).
 *
 * 대분류가 아니라 중분류로 거르는 이유가 있다. 수집한 2,842건을 실제로 집계해 보니
 * 대분류가 두 세대로 섞여 있었다 — '복지문화'와 '금융･복지･문화', '참여권리'와 '참여･기반'이
 * 같은 중분류를 나눠 갖고 있다. 대분류로 거르면 어느 쪽을 골라도 절반을 놓친다.
 */
@ConfigurationProperties(prefix = "curation")
public class CurationProperties {

    /** 서비스 대상 중분류. 값에 공백이 있으므로 설정에서 콤마로 나눈다. */
    private List<String> mediumCategories = new ArrayList<>();

    /** 큐레이션 상한. 9월 커리큘럼에서 30건으로 고정(D-004). */
    private int limit = 30;

    /** 승인 상태 코드. 승인된 정책만 적재한다(D-007). */
    private String approvedStatusCode = "0044002";

    public List<String> getMediumCategories() { return mediumCategories; }
    public void setMediumCategories(List<String> mediumCategories) { this.mediumCategories = mediumCategories; }

    public int getLimit() { return limit; }
    public void setLimit(int limit) { this.limit = limit; }

    public String getApprovedStatusCode() { return approvedStatusCode; }
    public void setApprovedStatusCode(String approvedStatusCode) { this.approvedStatusCode = approvedStatusCode; }
}
