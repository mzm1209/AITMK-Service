package com.example.aitmk.model.domain;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * 线索管理字段白名单查询请求。
 */
@Data
public class ClueFieldQueryRequest {

    /**
     * key=controlId, value=过滤值。
     */
    private Map<String, Object> criteria = new HashMap<>();

    private Integer pageSize = 50;
    private Integer pageIndex = 1;
    private Integer listType = 0;
}
