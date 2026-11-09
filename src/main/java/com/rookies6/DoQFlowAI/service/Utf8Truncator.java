package com.rookies6.DoQFlowAI.service;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * 원문 보기 응답 크기 제한 < API 23 >
 *
 * 본문을 UTF-8 바이트 기준 상한(app.upload.view-max-bytes, 1MB)까지만 남긴다.
 * 인코더는 글자 하나를 나눠 쓰지 않으므로, 한글(3바이트)·이모지(4바이트) 중간에서 끊기지 않는다.
 */
public final class Utf8Truncator {

	private Utf8Truncator() {
	}

	/**
	 * @param text     원문 (null이면 그대로 null)
	 * @param maxBytes UTF-8 바이트 상한
	 */
	public static Result truncate(String text, int maxBytes) {
		if (text == null) {
			return new Result(null, false);
		}
		// 글자 하나는 UTF-8로 최대 3바이트(이모지는 char 2개에 4바이트)라, 이 길이 이하면 인코딩 없이 상한 안이다.
		if (text.length() <= maxBytes / 3) {
			return new Result(text, false);
		}
		CharBuffer in = CharBuffer.wrap(text);
		ByteBuffer out = ByteBuffer.allocate(maxBytes);
		// 짝이 맞지 않는 서로게이트가 있어도 오류로 멈추지 않고 대체 문자 크기로 세어 끝까지 진행한다.
		CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder()
				.onMalformedInput(CodingErrorAction.REPLACE)
				.onUnmappableCharacter(CodingErrorAction.REPLACE);
		CoderResult result = encoder.encode(in, out, true);
		if (!result.isOverflow()) {
			return new Result(text, false);
		}
		return new Result(text.substring(0, in.position()), true);
	}

	/**
	 * @param content   잘라낸 본문
	 * @param truncated 상한에서 잘렸으면 true
	 */
	public record Result(String content, boolean truncated) {
	}
}
