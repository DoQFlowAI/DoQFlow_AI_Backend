package com.rookies6.DoQFlowAI.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 원문 보기 1MB 자르기 단위 테스트 < API 23 > */
class Utf8TruncatorTest {

	@Test
	@DisplayName("상한 이하면 그대로 두고 truncated = false")
	void keepsTextWithinLimit() {
		Utf8Truncator.Result result = Utf8Truncator.truncate("1. 목적\n이 지침은", 1024);

		assertThat(result.content()).isEqualTo("1. 목적\n이 지침은");
		assertThat(result.truncated()).isFalse();
	}

	@Test
	@DisplayName("바이트 수가 상한과 정확히 같으면 자르지 않는다")
	void doesNotCutAtExactLimit() {
		String text = "가".repeat(4); // 12바이트

		Utf8Truncator.Result result = Utf8Truncator.truncate(text, 12);

		assertThat(result.content()).isEqualTo(text);
		assertThat(result.truncated()).isFalse();
	}

	@Test
	@DisplayName("상한을 넘으면 한글 중간에서 끊지 않고 앞부분만 남긴다")
	void cutsWithoutSplittingKorean() {
		String text = "가".repeat(10); // 30바이트

		Utf8Truncator.Result result = Utf8Truncator.truncate(text, 10); // 3글자(9바이트)까지만 들어감

		assertThat(result.content()).isEqualTo("가가가");
		assertThat(result.truncated()).isTrue();
		assertThat(result.content().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(10);
	}

	@Test
	@DisplayName("서로게이트 쌍 문자도 반으로 자르지 않는다")
	void doesNotSplitSurrogatePair() {
		// UTF-8 4바이트, Java char 2개인 문자(U+1F600). 글자를 소스에 직접 쓰면 편집기에서 깨질 수 있어 코드 번호로 만든다.
		String wide = new String(Character.toChars(0x1F600));
		String text = "ab" + wide.repeat(3); // 2 + 4*3 = 14바이트

		Utf8Truncator.Result result = Utf8Truncator.truncate(text, 9); // ab + 1글자(6바이트)까지

		assertThat(wide).hasSize(2);
		assertThat(result.content()).isEqualTo("ab" + wide);
		assertThat(result.truncated()).isTrue();
	}

	@Test
	@DisplayName("본문이 없으면 null")
	void nullStaysNull() {
		Utf8Truncator.Result result = Utf8Truncator.truncate(null, 10);

		assertThat(result.content()).isNull();
		assertThat(result.truncated()).isFalse();
	}

	@Test
	@DisplayName("실제 상한 1MB에서도 바이트 수를 넘지 않는다")
	void staysWithin1MbLimit() {
		int maxBytes = 1_048_576;
		String text = "가나다라\n".repeat(100_000); // 약 1.3MB

		Utf8Truncator.Result result = Utf8Truncator.truncate(text, maxBytes);

		assertThat(result.truncated()).isTrue();
		assertThat(result.content().getBytes(StandardCharsets.UTF_8).length)
				.isLessThanOrEqualTo(maxBytes)
				.isGreaterThan(maxBytes - 4);
	}
}
