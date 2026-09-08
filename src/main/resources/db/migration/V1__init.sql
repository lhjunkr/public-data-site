CREATE TABLE policy_raw (
      id            BIGINT      NOT NULL AUTO_INCREMENT,
      policy_no     VARCHAR(50) NOT NULL,
      raw_json      JSON        NOT NULL,
      fetched_at      DATETIME    NOT NULL,
      PRIMARY KEY   (id),
      UNIQUE KEY    uk_policy_raw_policy_no (policy_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE region (
    code        VARCHAR(50)     NOT NULL,
    sido        VARCHAR(50)     NOT NULL,
    name        VARCHAR(50)     NOT NULL,
    PRIMARY KEY (code)
)   ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE policy (
    id                  BIGINT          NOT NULL AUTO_INCREMENT,
    policy_no           VARCHAR(50)     NOT NULL,
    title               VARCHAR(255)    NOT NULL,
    category_large      VARCHAR(50),
    category_medium     VARCHAR(50),
    min_age             INT,
    max_age             INT,
    age_limit_yn        CHAR(1),
    description         TEXT,
    support_content     TEXT,
    apply_method        TEXT,
    required_documents  TEXT,
    extra_qualification TEXT,
    etc_matters         TEXT,
    apply_period_text   VARCHAR(255),
    apply_start_date    DATE,
    apply_end_date      DATE,
    apply_url           VARCHAR(500),
    ref_url             VARCHAR(500),
    supervising_org     VARCHAR(255),
    view_count          INT,
    created_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY         (id),
    UNIQUE KEY          uk_policy_policy_no (policy_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE policy_region (
    policy_id   BIGINT      NOT NULL,
    region_code VARCHAR(50) NOT NULL,
    PRIMARY KEY (policy_id, region_code),
    FOREIGN KEY (policy_id)     REFERENCES policy(id),
    FOREIGN KEY (region_code)   REFERENCES  region(code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;