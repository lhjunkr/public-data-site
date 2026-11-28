package kr.hojun.policymatch.normalize;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import kr.hojun.policymatch.category.CategoryCatalog;
import kr.hojun.policymatch.collect.Source;
import kr.hojun.policymatch.job.JobStatus;
import kr.hojun.policymatch.region.SidoNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 잡알리오 공공기관 채용공고 원본(policy_raw, source = alio)을 검색용 표로 옮긴다 (12/13, D-029).
 *
 * 칸 옮기기 (docs/erd.md "소스별로 칸에 넣는 값")
 *  - 제목 recrutPbancTtl · 기관 instNm · 접수 시작/마감 pbancBgngYmd/pbancEndYmd (YYYYMMDD)
 *  - 고용형태 hireTypeNmLst(콤마 여러 값) → 공공기관 채용 탭의 고용형태 칩(policy_category). 이름 → 칩은 묶음표(application.yml)
 *  - 채용 구분 recrutSeNm(신입 · 경력 · 신입+경력) → '신입'이 있으면 신입 가능 칩(recruit.entry)
 *  - 근무지 workRgnNmLst(콤마 여러 값, 시도 약칭) → 시도 코드 합집합(policy_sido). 통합 전 이름(광주 · 전남)도 sido.regions 에 있다
 *  - 나이 · 소득: 칸이 없어 비운다(D-029 ③). 취업 상태: 제한없음(0013010) 한 줄
 *  - 지원 자격 aplyQlfcCn → extra_qualification ('확인 필요', 12/5: 547건 모두 있음, 155건에 '만 NN세')
 *  - 고용형태 · 채용 구분 · 근무지 · 인원 · 학력 · 직무 · 대체인력 → support_content (화면에만 쓰는 값, D-029 ②)
 *  - 신청하기 = 잡알리오 공고 원문(recruitview.do?idx=번호), 참고 링크 = 기관 채용 페이지(srcUrl)
 *  - 전형 안내 글(scrnprcdrMthdExpln)은 싣지 않는다. 12/5 측정에서 담당자 이메일 28건 · 전화 30건이 섞여 있었다.
 *    전형 절차는 잡알리오 원문에서 본다(K-Startup · 마이홈의 연락처 칸을 싣지 않은 것과 같은 기준)
 *
 * 화면은 12/15 에 연다. 그때까지 공공기관 채용 탭이 닫혀 있어(application.yml) 이 공고들은 매칭 결과에 나오지 않는다.
 *
 * 다른 출처와 같은 공고: 합치지 않는다. 잡알리오는 공고 단위 원본이고, 지방공공기관(12/14)과는 기관 이름이 겹치지 않는다(12/5).
 * 같은 기관 · 같은 제목의 공고가 여러 번 올라온 경우(12/5: 15묶음 51건)도 공고 번호가 다르면 다른 공고다(D-029 ⑤).
 *
 * 선정 · 비활성 · 실패 격리 · 대량 변경 감지는 K-Startup 과 같은 규칙을 이 출처 안에서만 쓴다(D-014 · D-017 · D-019).
 *  - 수집할 때 이미 진행 중(ongoingYn = Y)만 받았다. 마감은 여기서 거르지 않고 매칭할 때 거른다(D-008).
 */
@Service
public class AlioNormalizer implements SourceNormalizer {

    private static final Logger log = LoggerFactory.getLogger(AlioNormalizer.class);
    private static final Source SOURCE = Source.ALIO;

    /** 공공기관 채용 탭 (application.yml 의 category.tabs). 칩 코드는 "recruit.칩" */
    static final String TAB_PREFIX = "recruit.";
    /** 채용 구분에 '신입'이 있는 공고에 붙인다. 원본 이름 매핑이 없는 칩이다(mediums: []) */
    static final String ENTRY_CHIP = "recruit.entry";
    static final String CATEGORY_LARGE = "공공기관 채용";

    /** 12/5 측정에서 나온 고용형태 5종. 묶음표에서 하나라도 칩을 잃으면 앱이 뜨지 않게 한다. */
    static final List<String> KNOWN_HIRE_TYPES = List.of("정규직", "무기계약직", "비정규직", "청년인턴(체험형)", "청년인턴(채용형)");

    /**
     * 잡알리오 공고 원문. idx 가 공고 번호(recrutPblntSn)다. 외부 채용 사이트에 걸린 잡알리오 원문 주소의 idx(300903 · 303713 ·
     * 306665)가 공고 번호(30만 번대)와 같은 범위인 것을 보고, 11/28 실제 공고 291587 을 브라우저로 열어 같은 공고임을 확인했다.
     */
    static final String DETAIL_URL = "https://job.alio.go.kr/recruitview.do?idx=";

    /**
     * 이 날 이후의 마감일은 "상시"로 본다. 12/5 측정에서 상시 모집 공고 1건이 마감일 20501231(게시 2020-07-21)이었다.
     * 다른 '상시' 공고들은 1년 뒤 날짜(2027년)를 실제 마감으로 적어 두어 그대로 쓴다. K-Startup 기준(2099)보다 앞당긴 이유.
     */
    static final LocalDate OPEN_ENDED_FROM = LocalDate.of(2050, 1, 1);
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.BASIC_ISO_DATE;

    /** D-019: 정책 단위 실패를 이만큼(또는 후보의 1%)까지는 건너뛰고, 넘으면 전체를 되돌린다. */
    static final int MIN_FAILURE_LIMIT = 10;

    private final PolicyStore store;
    private final SidoNames sidoNames;
    private final ObjectMapper objectMapper;
    private final CategoryCatalog categories;

    /**
     * 칩을 묶음표로 정하므로, 묶음표에서 칩이 사라지면 공고가 조용히 분류 없이 들어간다(12/15 에 탭을 열어도 안 보임).
     * 그래서 앱이 뜰 때 신입 가능 칩과 측정된 고용형태 5종의 칩이 공공기관 채용 탭에 있는지 확인한다(K-Startup · 마이홈과 같은 방식).
     * 탭은 아직 닫혀 있으므로 열린 칩이 아니라 모든 칩에서 찾는다.
     */
    public AlioNormalizer(PolicyStore store, SidoNames sidoNames, ObjectMapper objectMapper, CategoryCatalog categories) {
        if (!categories.allChipCodes().contains(ENTRY_CHIP)) {
            throw new IllegalStateException("잡알리오 칩 " + ENTRY_CHIP + " 이(가) 묶음표에 없습니다. "
                    + "application.yml 의 category.tabs 를 확인하십시오.");
        }
        for (String hireType : KNOWN_HIRE_TYPES) {
            if (hireTypeChips(categories, hireType).isEmpty()) {
                throw new IllegalStateException("잡알리오 고용형태 '" + hireType + "' 이(가) 공공기관 채용 탭의 칩에 없습니다. "
                        + "application.yml 의 category.tabs(recruit)를 확인하십시오.");
            }
        }
        this.store = store;
        this.sidoNames = sidoNames;
        this.objectMapper = objectMapper;
        this.categories = categories;
    }

    @Override
    public Source source() {
        return SOURCE;
    }

    @Override
    @Transactional
    public NormalizeResult normalize() {
        return run(false);
    }

    /** 같은 객체 안의 호출은 스프링 프록시를 거치지 않으므로, 바깥에서 부르는 두 메서드에 모두 트랜잭션을 붙인다. */
    @Override
    @Transactional
    public NormalizeResult normalizeAllowingMassDeactivation() {
        return run(true);
    }

    private NormalizeResult run(boolean allowMassDeactivation) {
        long startedMs = System.currentTimeMillis();
        // 상한의 기준은 "이번 정규화 전" 활성 건수다. upsert 가 다시 나타난 공고를 활성으로 바꾸므로 먼저 센다
        int activeBefore = store.countActive(SOURCE);
        List<String> rawRows = store.rawSinceLastSuccess(SOURCE);
        log.info("잡알리오 정규화 후보 {}건", rawRows.size());

        int policies = 0, sidoLinks = 0, jobLinks = 0, categoryLinks = 0;
        int noRegion = 0, uncategorized = 0, skipped = 0;
        Set<String> unknownRegions = new TreeSet<>();
        Set<String> unknownHireTypes = new TreeSet<>();
        List<String> selectedNos = new ArrayList<>();
        List<String> failedNos = new ArrayList<>();

        for (String rawJson : rawRows) {
            JsonNode n = objectMapper.readTree(rawJson);
            String policyNo = text(n, "recrutPblntSn");
            if (policyNo == null || policyNo.isBlank()) {
                skipped++;
                continue;
            }

            Set<String> sidos = new LinkedHashSet<>();
            for (String region : split(text(n, "workRgnNmLst"))) {
                Set<String> codes = sidoNames.codesOf(region);
                if (codes.isEmpty()) {
                    unknownRegions.add(region);
                }
                sidos.addAll(codes);
            }
            if (sidos.isEmpty()) {
                // 근무지를 하나도 못 찾으면 어느 시도 검색에도 나오지 않는다. 설정(sido.regions)에 넣어야 한다
                noRegion++;
            }

            String hireTypes = text(n, "hireTypeNmLst");
            Set<String> chips = hireTypeChips(categories, hireTypes);
            for (String name : split(hireTypes)) {
                if (hireTypeChips(categories, name).isEmpty()) {
                    unknownHireTypes.add(name);
                }
            }
            if (isEntryOpen(text(n, "recrutSeNm"))) {
                chips.add(ENTRY_CHIP);
            }
            if (chips.isEmpty()) {
                // 칩이 없으면 탭을 열어도 결과에 나오지 않는다(D-018). 싣기는 하고 수를 남긴다
                uncategorized++;
            }

            int[] links;
            try {
                long policyId = store.upsert(SOURCE, toRow(n, policyNo));
                links = new int[] {
                        store.syncLinks("policy_sido", "sido", policyId, sidos),
                        store.syncLinks("policy_job", "job_code", policyId, Set.of(JobStatus.UNLIMITED_CODE)),
                        store.syncLinks("policy_category", "category_code", policyId, chips) };
            } catch (DataAccessException e) {
                // D-019: 한 공고가 실패해도 나머지는 계속한다. 실패한 공고는 선정 목록에 없으므로 비활성이 된다
                failedNos.add(policyNo);
                log.warn("잡알리오 공고 {} 정규화 실패 — 건너뜀: {}", policyNo, e.getMostSpecificCause().getMessage());
                continue;
            }
            policies++;
            selectedNos.add(policyNo);
            sidoLinks += links[0];
            jobLinks += links[1];
            categoryLinks += links[2];
        }

        int maxFailures = Math.max(MIN_FAILURE_LIMIT, rawRows.size() / 100);
        if (failedNos.size() > maxFailures) {
            throw new IllegalStateException("잡알리오 정규화 실패가 " + failedNos.size() + "건으로 허용치(" + maxFailures
                    + "건)를 넘어 전체를 되돌립니다. 처음 실패한 공고: " + failedNos.get(0));
        }
        if (selectedNos.isEmpty()) {
            throw new IllegalStateException("잡알리오 선정 공고가 0건이라 비활성 처리를 하지 않고 중단합니다. "
                    + "성공한 수집(collect_run 의 alio SUCCESS)이 있는지, policy_raw 가 비어 있지 않은지 확인하십시오.");
        }

        // D-019 "12/8 추가": 바꾸기 전에 몇 건이 꺼질지 세고, 상한을 넘으면 바꾸지 않고 멈춘다
        int toDeactivate = store.countToDeactivate(SOURCE, selectedNos);
        if (allowMassDeactivation) {
            if (toDeactivate > MassDeactivationGuard.limitFor(activeBefore)) {
                log.warn("잡알리오 대량 비활성 강제 반영 — 활성 {}건 중 {}건 비활성 (상한 {}건)",
                        activeBefore, toDeactivate, MassDeactivationGuard.limitFor(activeBefore));
            }
        } else {
            MassDeactivationGuard.check(SOURCE, activeBefore, toDeactivate);
        }
        int deactivated = store.deactivateOthers(SOURCE, selectedNos);

        if (!unknownRegions.isEmpty()) {
            log.warn("잡알리오 시도 매핑에 없는 근무지 이름 {}개 (근무지를 하나도 못 찾은 공고 {}건): {}",
                    unknownRegions.size(), noRegion, unknownRegions);
        }
        if (!unknownHireTypes.isEmpty()) {
            log.warn("잡알리오 묶음표에 없는 고용형태 {}개 (칩이 하나도 없는 공고 {}건): {}",
                    unknownHireTypes.size(), uncategorized, unknownHireTypes);
        }
        log.info("잡알리오 정규화 완료 — policy={} sido={} job={} category={} 지역없음={} 분류없음={} skipped={} failed={} "
                        + "deactivated={} ({}ms)",
                policies, sidoLinks, jobLinks, categoryLinks, noRegion, uncategorized, skipped, failedNos.size(),
                deactivated, System.currentTimeMillis() - startedMs);
        return new NormalizeResult(rawRows.size(), policies, sidoLinks, jobLinks, categoryLinks,
                uncategorized, skipped, failedNos.size(), deactivated, 0);
    }

    /** 원본 한 건을 policy 행 값으로 바꾼다. */
    PolicyRow toRow(JsonNode n, String policyNo) {
        String startText = text(n, "pbancBgngYmd");
        String endText = text(n, "pbancEndYmd");
        LocalDate start = date(startText);
        LocalDate end = date(endText);
        boolean openEnded = end == null && parse(endText) != null;
        String period = null;
        if (start != null && openEnded) {
            period = startText + " ~ 상시";
        } else if (start != null && end != null) {
            period = startText + " ~ " + endText;
        } else if (end != null) {
            period = "~ " + endText;
        }

        return new PolicyRow(
                policyNo,
                TextCleaner.clean(text(n, "recrutPbancTtl")),
                CATEGORY_LARGE,
                spaced(text(n, "hireTypeNmLst")),
                null,
                null,
                "N",
                null, null, null, null,
                null,
                supportContent(n),
                null,
                null,
                TextCleaner.clean(text(n, "aplyQlfcCn")),
                preference(n),
                period,
                start,
                end,
                detailUrl(policyNo),
                orgUrl(text(n, "srcUrl")),
                TextCleaner.clean(text(n, "instNm")),
                null);
    }

    /**
     * 상세 조회(지원 내용 보기)에 보일 글. 칸에 없는 채용 정보를 사람이 읽을 순서로 모은다(D-029 ②).
     * 전형 안내(scrnprcdrMthdExpln)는 담당자 연락처가 섞여 있어 넣지 않고, 원문에서 보라고 안내한다.
     */
    String supportContent(JsonNode n) {
        StringBuilder out = new StringBuilder();
        line(out, "고용형태", spaced(text(n, "hireTypeNmLst")));
        line(out, "채용 구분", recruitType(text(n, "recrutSeNm")));
        line(out, "근무지", spaced(text(n, "workRgnNmLst")));
        String people = text(n, "recrutNope");
        line(out, "채용 인원", people == null || !people.strip().matches("[1-9]\\d*") ? null : people.strip() + "명");
        line(out, "학력", spaced(text(n, "acbgCondNmLst")));
        String ncs = spaced(text(n, "ncsCdNmLst"));
        line(out, "직무 분야", ncs == null ? null : ncs.replace('.', '·'));
        if ("Y".equalsIgnoreCase(String.valueOf(text(n, "replmprYn")).strip())) {
            line(out, "대체인력", "예 (휴직 등의 대체 채용)");
        }
        line(out, "기관", text(n, "instNm"));
        line(out, "전형 절차 · 문의", "잡알리오 공고 원문에서 확인");
        section(out, "지원 자격", text(n, "aplyQlfcCn"));
        section(out, "우대 사항", text(n, "prefCn") != null ? text(n, "prefCn") : text(n, "prefCondCn"));
        section(out, "결격 사유", text(n, "disqlfcRsn"));
        String body = out.toString().strip();
        return body.isEmpty() ? null : body;
    }

    /** 우대 사항. 긴 글(prefCn)이 없으면 요약(prefCondCn). 12/5: 둘 다 있는 경우 내용이 같았다 */
    private String preference(JsonNode n) {
        String pref = TextCleaner.clean(text(n, "prefCn"));
        return pref != null ? pref : TextCleaner.clean(text(n, "prefCondCn"));
    }

    /** 고용형태 이름(콤마 여러 값)을 공공기관 채용 탭의 칩으로. 다른 탭의 칩은 붙이지 않는다(이름이 우연히 겹쳐도). */
    static Set<String> hireTypeChips(CategoryCatalog categories, String hireTypes) {
        Set<String> chips = new LinkedHashSet<>();
        for (String chip : categories.chipsOf(hireTypes)) {
            if (chip.startsWith(TAB_PREFIX)) {
                chips.add(chip);
            }
        }
        return chips;
    }

    /** 신입 · 신입+경력 → 신입 지원 가능. 경력만이면 아니다. */
    static boolean isEntryOpen(String recruitType) {
        return recruitType != null && recruitType.contains("신입");
    }

    /** "신입+경력" → "신입 + 경력" */
    private static String recruitType(String value) {
        return value == null ? null : value.strip().replaceAll("\\s*\\+\\s*", " + ");
    }

    /** 잡알리오 공고 원문 주소. 번호가 숫자일 때만 만든다. */
    static String detailUrl(String policyNo) {
        return policyNo != null && policyNo.strip().matches("\\d+") ? DETAIL_URL + policyNo.strip() : null;
    }

    /**
     * 기관 채용 페이지(srcUrl). 12/5 측정 547건 중 28건이 http 로 시작하지 않았다:
     * 앞에 공백 · 탭이 붙은 주소(5건)는 공백을 지워 쓰고, 'www.' 로 시작하는 주소(9건)는 https:// 를 붙인다.
     * '.' · '없음' · '해당없음' · '/' 같은 값(14건)은 링크로 만들지 않는다.
     */
    static String orgUrl(String url) {
        String u = url == null ? null : url.strip();
        if (u == null || u.isEmpty()) {
            return null;
        }
        if (u.matches("(?i)^https?://\\S+")) {
            return u;
        }
        if (u.matches("(?i)^www\\.[\\w.-]+\\.[a-z]{2,}(/\\S*)?$")) {
            return "https://" + u;
        }
        return null;
    }

    private static List<String> split(String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null) {
            return out;
        }
        for (String token : csv.split(",")) {
            String t = token.strip();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    /** "경남,경기" → "경남, 경기" */
    private static String spaced(String csv) {
        List<String> parts = split(TextCleaner.clean(csv));
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    private static void line(StringBuilder out, String label, String value) {
        String v = TextCleaner.clean(value);
        if (v != null) {
            out.append(label).append(": ").append(v).append('\n');
        }
    }

    private static void section(StringBuilder out, String label, String value) {
        String v = TextCleaner.clean(value);
        if (v != null) {
            out.append('\n').append('[').append(label).append("]\n").append(v).append('\n');
        }
    }

    /** YYYYMMDD → 날짜. 2050년 이후(상시)나 읽을 수 없는 값은 null. */
    static LocalDate date(String yyyymmdd) {
        LocalDate d = parse(yyyymmdd);
        return d == null || !d.isBefore(OPEN_ENDED_FROM) ? null : d;
    }

    private static LocalDate parse(String yyyymmdd) {
        if (yyyymmdd == null || !yyyymmdd.strip().matches("\\d{8}")) {
            return null;
        }
        try {
            return LocalDate.parse(yyyymmdd.strip(), YYYYMMDD);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * 칸의 글을 JSON 이스케이프를 푼 그대로 꺼낸다(\r\n → 줄바꿈). 숫자 칸(recrutPblntSn · recrutNope)은 숫자 그대로의 글자.
     * Jackson 2 → 3 에서 값 추출 메서드 이름이 바뀌어, 버전과 상관없는 toString() + readValue 로 꺼낸다(K-Startup 과 같은 이유).
     */
    private String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return null;
        }
        String raw = v.toString();
        String value = raw.startsWith("\"") ? objectMapper.readValue(raw, String.class) : raw;
        return value.isBlank() ? null : value;
    }
}
