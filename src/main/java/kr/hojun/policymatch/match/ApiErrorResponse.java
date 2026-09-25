package kr.hojun.policymatch.match;

/** 오류 응답 본문. 프론트가 message 를 그대로 사용자에게 보여줄 수 있게 한다. */
public record ApiErrorResponse(int status, String error, String message) {}
