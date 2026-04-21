package com.example.aitmk.model.domain;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 线索管理高级查询请求（透传 CRM getFilterRows 参数核心字段）。
 */
@Data
public class ClueQueryRequest {

    private List<Map<String, Object>> filters = new ArrayList<>();
    private Integer pageSize = 50;
    private Integer pageIndex = 1;
    private Integer listType = 0;
    private List<Map<String, Object>> sortControls = new ArrayList<>();
}
