package com.sqlagent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 工具信息DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ToolInfo {

    /**
     * 工具ID（用于前端标识）
     */
    private String id;

    /**
     * 工具名称
     */
    private String name;

    /**
     * 工具图标（emoji）
     */
    private String icon;

    /**
     * 工具描述
     */
    private String description;

    /**
     * 工具分类
     */
    private String category;
}
