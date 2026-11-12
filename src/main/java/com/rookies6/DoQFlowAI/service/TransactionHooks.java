package com.rookies6.DoQFlowAI.service;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 트랜잭션 결과에 맞춰 실행할 작업 < API 11, API 18, API 21 >
 *
 * 파일·벡터처럼 DB 밖의 자원은 트랜잭션으로 되돌릴 수 없다. 그래서
 *   지우는 작업은 커밋이 끝난 뒤에 하고 (DB 삭제가 롤백됐는데 파일만 지워지는 일 방지),
 *   만든 파일은 롤백되면 지운다 (DB에 행이 없는 고아 파일 방지).
 * 트랜잭션이 없으면(단위 테스트) afterCommit은 바로 실행하고, onRollback은 등록하지 않는다.
 */
final class TransactionHooks {

	private TransactionHooks() {
	}

	static void afterCommit(Runnable task) {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					task.run();
				}
			});
		} else {
			task.run();
		}
	}

	static void onRollback(Runnable task) {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCompletion(int status) {
					if (status == STATUS_ROLLED_BACK) {
						task.run();
					}
				}
			});
		}
	}
}
