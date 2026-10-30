package kr.hojun.policymatch.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** 취업 상태 선택지가 공식 코드표(jobCd 0013001~0013010)와 맞는지 본다 (D-021). */
class JobStatusTest {

    @Test
    void 선택지는_공식_코드_9개이고_제한없음은_빠진다() {
        Set<String> codes = Arrays.stream(JobStatus.values()).map(JobStatus::code).collect(Collectors.toSet());

        assertEquals(9, codes.size(), "코드가 겹치지 않는다");
        for (int n = 1; n <= 9; n++) {
            assertTrue(codes.contains(String.format("00130%02d", n)), "0013001 ~ 0013009");
        }
        assertTrue(JobStatus.ofCode(JobStatus.UNLIMITED_CODE).isEmpty());
    }

    @Test
    void 코드로_찾고_없는_코드는_빈값() {
        assertEquals(JobStatus.UNEMPLOYED, JobStatus.ofCode("0013003").orElseThrow());
        assertEquals("미취업자", JobStatus.UNEMPLOYED.label());
        assertTrue(JobStatus.ofCode("0013011").isEmpty());
        assertTrue(JobStatus.ofCode(null).isEmpty());
    }
}
