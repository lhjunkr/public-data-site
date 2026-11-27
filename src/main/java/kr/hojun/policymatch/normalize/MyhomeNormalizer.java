package kr.hojun.policymatch.normalize;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import kr.hojun.policymatch.category.CategoryCatalog;
import kr.hojun.policymatch.collect.MyhomeCollector.Kind;
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
 * 마이홈 공공주택 모집공고 원본(policy_raw, source = myhome)을 검색용 표로 옮긴다 (12/11, D-029).
 *
 * 원본 한 행 = (공고, 주택) 하나이고, raw_json 은 그 주택의 지역 행 배열이다(MyhomeCollector).
 *
 * 칸 옮기기 (docs/erd.md "소스별로 칸에 넣는 값")
 *  - 제목 pblancNm · 기관 suplyInsttNm · 모집 시작/마감 beginDe/endDe
 *  - 신청 주소 url(LH 청약센터 등 공급 기관 페이지) · 참고 주소 pcUrl(마이홈 공고 상세, 12/12)
 *  - 지역: 지역 행마다 brtcNm(시도 정식 이름) → 시도 코드(SidoNames), 합집합을 policy_sido 로.
 *    시군구(signguNm)는 매칭에 쓰지 않고(D-027 시도 단위) 지원 내용의 "공급 지역"에 남긴다
 *  - 공급 유형: suplyTyNm(국민임대 · 행복주택 …, 임대만) → category_medium. 분양은 "공공분양"
 *  - 분류: 칩은 주거 · 공공임대·분양(housing.public) 하나(12/12, 사용자 결정). 12/11 에는 주거 · 주택 칩에 두었는데,
 *    조회수가 없어 온통청년 주거 정책 뒤에 묻혀 전용 칩으로 옮겼다. 공급 유형별 칩은 만들지 않는다(탭당 칩 3개 규칙)
 *  - 나이 · 소득: 원본에 없다(자격은 공고문 PDF). 비워서 지금 매칭 SQL 이 통과시키고, 자격은 '확인 필요'로 둔다(D-001 · D-003)
 *  - 취업 상태: 제한없음(0013010) 한 줄
 *  - 금액(12/12): 임대는 보증금 rentGtn(= 계약금 enty + 잔금 surlus) · 월 임대료 mtRntchrg, 분양은 계약금 enty · 중도금 prtpay ·
 *    잔금 surlus 를 '원'으로 싣는다. 단위는 원본 안의 관계로 판단했다(명세 미확인): 11/27 원본에서 보증금이 있는 233행 중
 *    226행이 보증금 = 계약금 + 중도금 + 잔금과 정확히 같고, 월 임대료가 3만 ~ 56.9만이라 원 단위일 때만 말이 된다. 어느 주택형(면적)의 값인지는 원본에 없어 '공고문 확인' 안내를 붙인다.
 *    지역 행마다 값이 다르면 숫자 대신 '지역마다 다름'으로 적는다. 0 이나 빈 값은 싣지 않는다
 *
 * 정정공고 (D-029 ⑤ "같은 식별자가 있을 때만")
 *  - 어떤 공고의 beforePblancId 가 다른 공고의 pblancId 를 가리키면, 가리켜진 쪽은 고치기 전 공고다. 싣지 않고 duplicates 로 센다.
 *    이미 실려 있던 이전 공고는 선정 목록에 없으므로 비활성이 된다. 12/5: 정정 81행 중 77행은 이전 공고도 응답에 있었다.
 *  - 임대 · 분양은 번호 체계가 같다는 보장이 없어 같은 구분 안에서만 맞춘다.
 *
 * 선정 · 비활성 · 실패 격리 · 대량 변경 감지는 K-Startup 정규화기와 같은 규칙을 이 출처 안에서만 쓴다(D-014 · D-017 · D-019).
 *  - 승인 상태 칸이 없다. 서버가 접수가 끝나지 않은 공고만 준다.
 *  - 마감은 여기서 거르지 않고 매칭할 때 거른다(D-008). 접수 시작 전 공고(12/5: 임대 117개 중 67개)도 싣는다.
 */
@Service
public class MyhomeNormalizer implements SourceNormalizer {

    private static final Logger log = LoggerFactory.getLogger(MyhomeNormalizer.class);
    private static final Source SOURCE = Source.MYHOME;

    /** 화면 칩: 주거 탭 · 공공임대·분양 칩 (application.yml 의 category.tabs, 12/12) */
    static final String CHIP = "housing.public";
    static final String CATEGORY_LARGE = "주거";

    /** 금액 아래에 붙이는 안내. 원본에는 어느 주택형의 금액인지가 없다. */
    static final String AMOUNT_NOTICE = "금액은 마이홈이 제공하는 값이며, 주택형(면적)에 따라 다를 수 있으니 모집공고문에서 확인하십시오.";

    /** 단지명 · 주소를 몇 개까지 적을지. 전세임대 · 매입임대는 지역 행이 최대 16개라 다 적으면 길어진다. */
    static final int MAX_LISTED = 3;

    /** extra_qualification 고정 문구. 비어 있지 않으므로 화면에 '추가 자격 확인 필요'가 뜬다(D-003). */
    static final String QUALIFICATION_NOTICE =
            "입주 자격(나이 · 소득 · 자산 · 무주택 여부 등)은 모집공고문에서 확인해야 합니다.";

    /** 이 날 이후의 마감일은 "상시"로 본다(K-Startup 과 같은 기준). */
    static final LocalDate OPEN_ENDED_FROM = LocalDate.of(2099, 1, 1);
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.BASIC_ISO_DATE;

    /** D-019: 정책 단위 실패를 이만큼(또는 후보의 1%)까지는 건너뛰고, 넘으면 전체를 되돌린다. */
    static final int MIN_FAILURE_LIMIT = 10;

    private final PolicyStore store;
    private final SidoNames sidoNames;
    private final ObjectMapper objectMapper;

    /** 칩이 묶음표에서 사라지거나 닫히면 공고가 조용히 검색에서 빠지므로, 앱이 뜰 때 확인한다(K-Startup 과 같은 방식). */
    public MyhomeNormalizer(PolicyStore store, SidoNames sidoNames, ObjectMapper objectMapper,
                            CategoryCatalog categories) {
        if (!categories.openChipCodes().contains(CHIP)) {
            throw new IllegalStateException("마이홈 칩 " + CHIP + " 이(가) 묶음표의 열린 칩에 없습니다. "
                    + "application.yml 의 category.tabs 를 확인하십시오.");
        }
        this.store = store;
        this.sidoNames = sidoNames;
        this.objectMapper = objectMapper;
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
        List<PolicyStore.RawRow> rawRows = store.rawRowsSinceLastSuccess(SOURCE);

        int skipped = 0;
        List<Unit> units = new ArrayList<>(rawRows.size());
        for (PolicyStore.RawRow raw : rawRows) {
            Unit unit = parse(raw);
            if (unit == null) {
                skipped++;
                continue;
            }
            units.add(unit);
        }
        Set<String> superseded = supersededPosts(units);
        // 한 공고에 주택(단지)이 여럿이면 제목이 같은 카드가 여러 장 나온다(11/27: 320건 중 239건). 그 경우만 제목에 단지명을 붙인다
        Map<String, Integer> unitsPerPost = new HashMap<>();
        for (Unit unit : units) {
            unitsPerPost.merge(postKey(unit.kind(), unit.pblancId()), 1, Integer::sum);
        }
        log.info("마이홈 정규화 후보 {}건 · 정정공고가 가리키는 이전 공고 {}개", units.size(), superseded.size());

        int policies = 0, sidoLinks = 0, jobLinks = 0, categoryLinks = 0, noRegion = 0, duplicates = 0;
        Set<String> unknownRegions = new TreeSet<>();
        List<String> selectedNos = new ArrayList<>();
        List<String> failedNos = new ArrayList<>();

        for (Unit unit : units) {
            if (superseded.contains(postKey(unit.kind(), unit.pblancId()))) {
                duplicates++;
                log.info("마이홈 {} 는 정정공고가 가리키는 이전 공고라 싣지 않음", unit.policyNo());
                continue;
            }

            Set<String> sidos = new LinkedHashSet<>();
            for (JsonNode row : unit.rows()) {
                String brtc = TextCleaner.clean(text(row, "brtcNm"));
                Set<String> codes = sidoNames.codesOf(brtc);
                if (codes.isEmpty()) {
                    unknownRegions.add(String.valueOf(brtc));
                }
                sidos.addAll(codes);
            }
            if (sidos.isEmpty()) {
                // 모르는 시도 이름이면 어느 시도 검색에도 나오지 않는다. 설정(sido.regions)에 넣어야 한다
                noRegion++;
            }

            int[] links;
            try {
                boolean sharedTitle = unitsPerPost.getOrDefault(postKey(unit.kind(), unit.pblancId()), 1) > 1;
                long policyId = store.upsert(SOURCE, toRow(unit, sharedTitle));
                links = new int[] {
                        store.syncLinks("policy_sido", "sido", policyId, sidos),
                        store.syncLinks("policy_job", "job_code", policyId, Set.of(JobStatus.UNLIMITED_CODE)),
                        store.syncLinks("policy_category", "category_code", policyId, Set.of(CHIP)) };
            } catch (DataAccessException e) {
                // D-019: 한 공고가 실패해도 나머지는 계속한다. 실패한 공고는 선정 목록에 없으므로 비활성이 된다
                failedNos.add(unit.policyNo());
                log.warn("마이홈 {} 정규화 실패 — 건너뜀: {}", unit.policyNo(), e.getMostSpecificCause().getMessage());
                continue;
            }
            policies++;
            selectedNos.add(unit.policyNo());
            sidoLinks += links[0];
            jobLinks += links[1];
            categoryLinks += links[2];
        }

        int maxFailures = Math.max(MIN_FAILURE_LIMIT, rawRows.size() / 100);
        if (failedNos.size() > maxFailures) {
            throw new IllegalStateException("마이홈 정규화 실패가 " + failedNos.size() + "건으로 허용치(" + maxFailures
                    + "건)를 넘어 전체를 되돌립니다. 처음 실패한 공고: " + failedNos.get(0));
        }
        if (selectedNos.isEmpty()) {
            throw new IllegalStateException("마이홈 선정 공고가 0건이라 비활성 처리를 하지 않고 중단합니다. "
                    + "성공한 수집(collect_run 의 myhome SUCCESS)이 있는지, policy_raw 가 비어 있지 않은지 확인하십시오.");
        }

        // D-019 "12/8 추가": 바꾸기 전에 몇 건이 꺼질지 세고, 상한을 넘으면 바꾸지 않고 멈춘다
        int toDeactivate = store.countToDeactivate(SOURCE, selectedNos);
        if (allowMassDeactivation) {
            if (toDeactivate > MassDeactivationGuard.limitFor(activeBefore)) {
                log.warn("마이홈 대량 비활성 강제 반영 — 활성 {}건 중 {}건 비활성 (상한 {}건)",
                        activeBefore, toDeactivate, MassDeactivationGuard.limitFor(activeBefore));
            }
        } else {
            MassDeactivationGuard.check(SOURCE, activeBefore, toDeactivate);
        }
        int deactivated = store.deactivateOthers(SOURCE, selectedNos);

        if (!unknownRegions.isEmpty()) {
            log.warn("마이홈 시도 매핑에 없는 시도 이름 {}개(시도 연결 없는 공고 {}건): {}", unknownRegions.size(), noRegion,
                    unknownRegions);
        }
        log.info("마이홈 정규화 완료 — policy={} sido={} job={} category={} 지역없음={} skipped={} failed={} "
                        + "duplicates(정정 전 공고)={} deactivated={} ({}ms)",
                policies, sidoLinks, jobLinks, categoryLinks, noRegion, skipped, failedNos.size(), duplicates,
                deactivated, System.currentTimeMillis() - startedMs);
        // uncategorized: 마이홈은 칩을 늘 붙이므로 0
        return new NormalizeResult(rawRows.size(), policies, sidoLinks, jobLinks, categoryLinks,
                0, skipped, failedNos.size(), deactivated, duplicates);
    }

    /** 원본 한 행을 (구분, 공고 번호, 지역 행들)로 읽는다. 읽을 수 없으면 null. */
    Unit parse(PolicyStore.RawRow raw) {
        Kind kind = Kind.ofPolicyNo(raw.policyNo());
        if (kind == null) {
            log.warn("마이홈 원본 번호 {} 의 접두어(rent- · sale-)를 몰라 건너뜁니다.", raw.policyNo());
            return null;
        }
        JsonNode root = objectMapper.readTree(raw.rawJson());
        List<JsonNode> rows = new ArrayList<>();
        if (root.isArray()) {
            for (int i = 0; i < root.size(); i++) {
                rows.add(root.get(i));
            }
        } else if (root.isObject()) {
            rows.add(root);
        }
        if (rows.isEmpty()) {
            return null;
        }
        String pblancId = text(rows.get(0), "pblancId");
        if (pblancId == null) {
            return null;
        }
        return new Unit(raw.policyNo(), kind, pblancId.strip(), rows);
    }

    /** 정정공고들이 가리키는 이전 공고(구분 + pblancId). 자기 자신을 가리키는 값은 무시한다. */
    Set<String> supersededPosts(List<Unit> units) {
        Set<String> out = new HashSet<>();
        for (Unit unit : units) {
            for (JsonNode row : unit.rows()) {
                String before = text(row, "beforePblancId");
                if (before != null && !before.strip().equals(unit.pblancId())) {
                    out.add(postKey(unit.kind(), before.strip()));
                }
            }
        }
        return out;
    }

    private static String postKey(Kind kind, String pblancId) {
        return kind.prefix() + ":" + pblancId;
    }

    /**
     * (공고, 주택) 하나를 policy 행 값으로 바꾼다. 공고 공통 칸은 첫 지역 행에서 읽는다.
     *
     * @param sharedTitle 같은 공고에 주택이 둘 이상이라 제목이 겹치면 true → 제목 뒤에 " · 단지명"을 붙인다(12/12)
     */
    PolicyRow toRow(Unit unit, boolean sharedTitle) {
        JsonNode first = unit.rows().get(0);
        String startText = digits(text(first, "beginDe"));
        String endText = digits(text(first, "endDe"));
        LocalDate start = date(startText);
        LocalDate end = date(endText);
        String period = null;
        if (start != null && end == null && rawDate(endText) != null) {
            period = startText + " ~ 상시";
        } else if (start != null && end != null) {
            period = startText + " ~ " + endText;
        } else if (end != null) {
            period = "~ " + endText;
        }

        return new PolicyRow(
                unit.policyNo(),
                title(unit, sharedTitle),
                CATEGORY_LARGE,
                supplyType(unit),
                null,
                null,
                "N",
                null, null, null, null,
                null,
                supportContent(unit),
                null,
                null,
                QUALIFICATION_NOTICE,
                null,
                period,
                start,
                end,
                httpUrl(text(first, "url")),
                httpUrl(text(first, "pcUrl")),
                TextCleaner.clean(text(first, "suplyInsttNm")),
                null);
    }

    /** 제목 칸 길이(VARCHAR 255). 단지명을 붙여 넘으면 자른다. */
    static final int TITLE_MAX = 255;

    /** 공고명. 제목이 다른 주택과 겹치고 단지명이 있으면 " · 단지명"을 붙인다. */
    String title(Unit unit, boolean sharedTitle) {
        String name = TextCleaner.clean(text(unit.rows().get(0), "pblancNm"));
        String complex = TextCleaner.clean(text(unit.rows().get(0), "hsmpNm"));
        if (name == null || !sharedTitle || complex == null) {
            return name;
        }
        String titled = name + " · " + complex;
        return titled.length() <= TITLE_MAX ? titled : titled.substring(0, TITLE_MAX);
    }

    /** 공급 유형: 임대는 suplyTyNm(국민임대 · 행복주택 …), 없으면 구분 이름(공공임대 · 공공분양). */
    String supplyType(Unit unit) {
        String type = TextCleaner.clean(text(unit.rows().get(0), "suplyTyNm"));
        return type != null ? type : unit.kind().label();
    }

    /**
     * 상세 조회(지원 내용 보기)에 보일 글. 표 칸에 없는 공고 정보를 사람이 읽을 순서로 모은다(D-029 ②).
     * 지역 행이 여러 개면 "공급 지역"에 시군구와 공급 호수를 한 줄씩 적는다.
     */
    String supportContent(Unit unit) {
        JsonNode first = unit.rows().get(0);
        StringBuilder out = new StringBuilder();
        out.append("안내: ").append(QUALIFICATION_NOTICE).append('\n');
        line(out, "구분", unit.kind().label());
        line(out, "공급 유형", supplyType(unit));
        line(out, "주택 유형", distinct(unit, "houseTyNm"));
        line(out, "단지명", listed(unit, "hsmpNm"));
        line(out, "주소", listed(unit, "fullAdres"));
        line(out, "공고 상태", distinct(unit, "sttusNm"));
        line(out, "공급 기관", text(first, "suplyInsttNm"));
        line(out, "모집공고일", dotted(text(first, "rcritPblancDe")));
        line(out, "당첨자 발표일", dotted(text(first, "przwnerPresnatnDe")));

        Integer total = 0;
        List<String> regions = new ArrayList<>();
        for (JsonNode row : unit.rows()) {
            String brtc = TextCleaner.clean(text(row, "brtcNm"));
            String signgu = TextCleaner.clean(text(row, "signguNm"));
            String place = brtc == null ? "(지역 없음)" : (signgu == null ? brtc + " 전체" : brtc + " " + signgu);
            Integer count = number(text(row, "sumSuplyCo"));
            if (count != null && count == 0) {
                count = null; // 11/27: 지역 행 84개가 0 이다(전세임대 · 매입임대). "0호"는 '공급 없음'으로 읽혀 싣지 않는다
            }
            if (count == null) {
                total = null;
            } else if (total != null) {
                total += count;
            }
            String entry = count == null ? place : place + " · " + count + "호";
            if (!regions.contains(entry)) {
                regions.add(entry);
            }
        }
        if (total != null && total > 0) {
            line(out, "공급 호수", "합계 " + total + "호");
        }
        if (appendAmounts(out, unit)) {
            line(out, "금액 안내", AMOUNT_NOTICE);
        }
        out.append('\n').append("[공급 지역]\n");
        if (allSidosWhole(unit)) {
            // 전세임대 · 다자녀 · 신혼 전세처럼 16개 시도 '전체'가 한 줄씩 오는 공고(11/27 서울 마이홈 13건 중 4건). 16줄 대신 한 줄로
            out.append("전국 (").append(sidoNames.allCodes().size()).append("개 시도 전체)\n");
        } else {
            for (String r : regions) {
                out.append(r).append('\n');
            }
        }
        return out.toString().strip();
    }

    /**
     * 금액 줄을 붙이고, 하나라도 붙였으면 true. 임대: 보증금(계약금 · 잔금) · 월 임대료 / 분양: 계약금 · 중도금 · 잔금.
     */
    private boolean appendAmounts(StringBuilder out, Unit unit) {
        int before = out.length();
        if (unit.kind() == Kind.RENT) {
            Long deposit = amountValue(unit, "rentGtn");
            if (deposit != null) {
                // 계약금 + 중도금 + 잔금 = 보증금일 때만 내역을 붙인다. 11/27 원본: 보증금이 있는 233행 중 226행이 정확히 맞고
                // 7행은 안 맞았다. 안 맞는 내역을 같이 보여 주면 어느 쪽이 맞는지 사용자가 헷갈린다
                Long enty = amountValue(unit, "enty");
                Long prtpay = amountValue(unit, "prtpay");
                Long surlus = amountValue(unit, "surlus");
                boolean adds = deposit > 0 && enty != null && enty > 0 && surlus != null && surlus > 0
                        && (prtpay == null || prtpay > 0)
                        && enty + (prtpay == null ? 0 : prtpay) + surlus == deposit;
                String parts = adds ? " (계약금 " + won(enty) + (prtpay == null ? "" : " · 중도금 " + won(prtpay))
                        + " · 잔금 " + won(surlus) + ")" : "";
                line(out, "임대보증금", won(deposit) + parts);
            }
            line(out, "월 임대료", won(amountValue(unit, "mtRntchrg")));
        } else {
            line(out, "계약금", won(amountValue(unit, "enty")));
            line(out, "중도금", won(amountValue(unit, "prtpay")));
            line(out, "잔금", won(amountValue(unit, "surlus")));
        }
        return out.length() > before;
    }

    /** 지역 행마다 금액이 다를 때의 표시값. 실제 금액은 0보다 크므로 겹치지 않는다. */
    static final long DIFFERENT = -1L;

    /** 한 금액 칸의 값(원). 지역 행마다 다르면 DIFFERENT, 0 · 빈 값뿐이면 null. */
    private Long amountValue(Unit unit, String field) {
        Set<Long> values = new LinkedHashSet<>();
        for (JsonNode row : unit.rows()) {
            String t = text(row, field);
            if (t != null && t.strip().matches("\\d{1,15}")) {
                long v = Long.parseLong(t.strip());
                if (v > 0) {
                    values.add(v);
                }
            }
        }
        if (values.isEmpty()) {
            return null;
        }
        return values.size() == 1 ? values.iterator().next() : DIFFERENT;
    }

    /** 13192000 → "13,192,000원". 지역마다 다르면 "지역마다 다름", 값이 없으면 null(줄을 싣지 않음). */
    static String won(Long value) {
        if (value == null) {
            return null;
        }
        return value == DIFFERENT ? "지역마다 다름" : String.format(Locale.ROOT, "%,d원", value);
    }

    /** 서로 다른 값을 MAX_LISTED 개까지 적고, 더 있으면 "외 n곳"을 붙인다. */
    private String listed(Unit unit, String field) {
        Set<String> values = new LinkedHashSet<>();
        for (JsonNode row : unit.rows()) {
            String v = TextCleaner.clean(text(row, field));
            if (v != null) {
                values.add(v);
            }
        }
        if (values.isEmpty()) {
            return null;
        }
        List<String> shown = new ArrayList<>(values).subList(0, Math.min(MAX_LISTED, values.size()));
        String joined = String.join(", ", shown);
        return values.size() > MAX_LISTED ? joined + " 외 " + (values.size() - MAX_LISTED) + "곳" : joined;
    }

    /** 20261124 → 2026.11.24. 형식이 다르면 그대로. */
    static String dotted(String yyyymmdd) {
        String d = digits(yyyymmdd);
        return d != null && d.matches("\\d{8}") ? d.substring(0, 4) + "." + d.substring(4, 6) + "." + d.substring(6) : yyyymmdd;
    }

    /** 모든 지역 행이 시군구 없는 '시도 전체'이고, 그 시도들이 설정의 시도를 모두 덮으면 true. */
    private boolean allSidosWhole(Unit unit) {
        Set<String> codes = new LinkedHashSet<>();
        for (JsonNode row : unit.rows()) {
            if (TextCleaner.clean(text(row, "signguNm")) != null) {
                return false;
            }
            codes.addAll(sidoNames.codesOf(TextCleaner.clean(text(row, "brtcNm"))));
        }
        return codes.containsAll(sidoNames.allCodes());
    }

    /** 지역 행들에서 한 칸의 서로 다른 값을 순서대로 이어 붙인다. 보통 하나다. */
    private String distinct(Unit unit, String field) {
        Set<String> values = new LinkedHashSet<>();
        for (JsonNode row : unit.rows()) {
            String v = TextCleaner.clean(text(row, field));
            if (v != null) {
                values.add(v);
            }
        }
        return values.isEmpty() ? null : String.join(", ", values);
    }

    private static void line(StringBuilder out, String label, String value) {
        String v = TextCleaner.clean(value);
        if (v != null) {
            out.append(label).append(": ").append(v).append('\n');
        }
    }

    private static Integer number(String text) {
        if (text == null || !text.strip().matches("\\d{1,9}")) {
            return null;
        }
        return Integer.parseInt(text.strip());
    }

    /** http(s) 주소만 남긴다. 화면도 http(s) 가 아닌 주소는 링크로 만들지 않는다. */
    private static String httpUrl(String url) {
        String u = url == null ? null : url.strip();
        return u != null && u.matches("(?i)^https?://.+") ? u : null;
    }

    /** "2026-12-01" · "2026.12.01" 처럼 와도 숫자만 남긴다. 원본은 YYYYMMDD 다(12/5). */
    private static String digits(String text) {
        return text == null ? null : text.replaceAll("[^0-9]", "");
    }

    /** YYYYMMDD → 날짜. 2099년 이후(상시)나 읽을 수 없는 값은 null. */
    static LocalDate date(String yyyymmdd) {
        LocalDate d = rawDate(yyyymmdd);
        return d == null || !d.isBefore(OPEN_ENDED_FROM) ? null : d;
    }

    private static LocalDate rawDate(String yyyymmdd) {
        if (yyyymmdd == null || !yyyymmdd.matches("\\d{8}")) {
            return null;
        }
        try {
            return LocalDate.parse(yyyymmdd, YYYYMMDD);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * 칸의 글을 JSON 이스케이프를 푼 그대로 꺼낸다. 숫자 칸(houseSn · sumSuplyCo)은 숫자 그대로의 글자.
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

    /** 원본 한 행을 읽은 것: 번호 · 임대/분양 · 공고 번호 · 지역 행들 */
    record Unit(String policyNo, Kind kind, String pblancId, List<JsonNode> rows) {}
}
