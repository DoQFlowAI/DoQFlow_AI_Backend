package com.rookies6.DoQFlowAI.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** 원문 보기 1MB 자르기 단위 테스트 < API 23 > */
class Utf8TruncatorTest {

	@Test
	void 상한_이하면_그대로_두고_truncated_false() {
		Utf8Truncator.Result result = Utf8Truncator.truncate("1. 목적\n이 지침은", 1024);

		assertThat(result.content()).isEqualTo("1. 목적\n이 지침은");
		assertThat(result.truncated()).isFalse();
	}

	@Test
	void 바이트_수가_상한과_정확히_같으면_자르지_않는다() {
		String text = "가".repeat(4); // 12바이트

		Utf8Truncator.Result result = Utf8Truncator.truncate(text, 12);

		assertThat(result.content()).isEqualTo(text);
		assertThat(result.truncated()).isFalse();
	}

	@Test
	void 상한을_넘으면_한글_중간에서_끊지_않고_앞부분만_남긴다() {
		String text = "가".repeat(10); // 30바이트

		Utf8Truncator.Result result = Utf8Truncator.truncate(text, 10); // 3글자(9바이트)까지만 들어감

		assertThat(result.content()).isEqualTo("가가가");
		assertThat(result.truncated()).isTrue();
		assertThat(result.content().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(10);
	}

	@Test
	void 이모지처럼_char_두_개인_글자도_반으로_자르지_않는다() {
		// 이모지를 직접 쓰면 편집기·터미널에서 깨질 수 있어 유니코드 이스케이프로 쓴다 (U+1F600, UTF-8 4바이트)
		String emoji = " ";
		String text = "ab" + emoji.repeat(3); // 2 + 4*3 = 14바이트

		Utf8Truncator.Result result = Utf8Truncator.truncate(text, 9); // ab + 이모지 1개(6바이트)까지

		assertThat(result.content()).isEqualTo("ab" + emoji);
		assertThat(result.truncated()).isTrue();
	}

	@Test
	void 본문이_없으면_null() {
		Utf8Truncator.Result result = Utf8Truncator.truncate(null, 10);

		assertThat(result.content()).isNull();
		assertThat(result.truncated()).isFalse();
	}

	@Test
	void 실제_상한_1MB에서도_바이트_수를_넘지_않는다() {
		int maxBytes = 1_048_576;
		String text = "가나다라\n".repeat(100_000); // 약 1.3MB

		Utf8Truncator.Result result = Utf8Truncator.truncate(text, maxBytes);

		assertThat(result.truncated()).isTrue();
		assertThat(result.content().getBytes(StandardCharsets.UTF_8).length)
				.isLessThanOrEqualTo(maxBytes)
				.isGreaterThan(maxBytes - 4);
	}
}
