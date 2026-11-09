package com.rookies6.DoQFlowAI.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;


// 문서 목록 페이징 (GET, /documents?page=&size=&sort=) [ page, size, sort(createdAt,(asc|desc) ] <API 8>
public record DocumentListRequest(

		@Min(0)
		Integer page,

		@Min(1)
		@Max(50)
		Integer size,

		@Pattern(regexp = "createdAt,(asc|desc)")
		String sort
) {

	private static final int DEFAULT_PAGE = 0;
	private static final int DEFAULT_SIZE = 10;
	private static final String SORT_PROPERTY = "createdAt";

	// 검증 통과 시, pageable객체 생성
	public Pageable toPageable() {
		int pageValue = page == null ? DEFAULT_PAGE : page;
		int sizeValue = size == null ? DEFAULT_SIZE : size;
		Sort.Direction direction = (sort != null && sort.endsWith(",asc"))
				? Sort.Direction.ASC
				: Sort.Direction.DESC;
		return PageRequest.of(pageValue, sizeValue, Sort.by(direction, SORT_PROPERTY));
	}
}
