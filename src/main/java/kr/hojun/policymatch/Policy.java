package kr.hojun.policymatch;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

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
