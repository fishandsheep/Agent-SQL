package com.sqlagent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 值分布信息 DTO
 *
 * 表示单个值的出现次数和占比
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ValueDistribution {

    /**
     * 值
     */
    private Object value;

    /**
     * 出现次数
     */
    private Long count;

    /**
     * 占比（0-100）
     */
    private Double percentage;

    /**
     * 是否为热点值（占比 > 50%）
     */
    private boolean isHotspot;
}
