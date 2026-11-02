package kr.hojun.policymatch;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * policy 표의 JPA 엔티티.
 *
 * 화면과 API 는 이 엔티티를 쓰지 않는다(조회는 JdbcTemplate SQL). 남겨 둔 이유는 앱이 뜰 때
 * JPA 스키마 검증(ddl-auto=validate)이 Flyway 마이그레이션과 이 매핑이 어긋나지 않았는지 확인해 주기 때문이다.
 * 이 엔티티를 그대로 내보내던 전체 목록 API(GET /api/v1/policies)는 D-023 에서 삭제했다.
 */
@Entity
@Table(name = "policy")
public class Policy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "policy_no")
    private String policyNo;

    private String title;

    @Column(name = "category_large")
    private String categoryLarge;

    @Column(name = "min_age")
    private Integer minAge;

    @Column(name = "max_age")
    private Integer maxAge;

    protected Policy() {
    }

    public Long getId() { return id; }
    public String getPolicyNo() { return policyNo; }
    public String getTitle() { return title; }
    public String getCategoryLarge() { return categoryLarge; }
    public Integer getMinAge() { return minAge; }
    public Integer getMaxAge() { return maxAge; }
}
