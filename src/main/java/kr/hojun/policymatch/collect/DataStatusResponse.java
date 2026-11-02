package kr.hojun.policymatch.collect;

/**
 * @param updatedAt 정책 정보를 원본에서 마지막으로 받아 온 시각(한국 시간, "yyyy-MM-dd HH:mm"). 성공한 수집이 없으면 null
 */
public record DataStatusResponse(String updatedAt) {}
