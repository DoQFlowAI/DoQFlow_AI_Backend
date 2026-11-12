package com.rookies6.DoQFlowAI.exception;

/**
 * 이미 응답한 초대에 다시 응답 — 400 VALIDATION_ERROR < API 5.9 >
 *
 * <p>여기서 404를 쓰지 않는 이유: 본인의 초대임은 이미 확인된 뒤라
 * 존재 여부가 새로 드러나지 않는다. 화면에 "이미 처리된 초대입니다"를
 * 띄우려면 404와 구분돼야 한다.
 */
public class InvitationAlreadyRespondedException extends BusinessException {

    public InvitationAlreadyRespondedException(String detail) {
        super(ErrorCode.VALIDATION_ERROR, detail);
    }
}