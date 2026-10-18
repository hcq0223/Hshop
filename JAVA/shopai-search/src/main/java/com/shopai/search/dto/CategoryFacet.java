package com.shopai.search.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 分类分面桶（bucket）：某个分类下的命中数量
 * <p>
 * 说明：分面聚合仅返回 categoryId（用于点击后作为 post_filter 过滤条件），
 * 分类名称 categoryName 由前端用 /api/category/all 的本地映射表补全，避免在聚合里做子聚合。
 * <p>
 * 微服务拆分说明：属于 shopai-search 服务的分面搜索能力（新增）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CategoryFacet {

    /** 分类ID（对应 products.category_id） */
    private Integer categoryId;

    /** 该分类下的命中数量（doc_count） */
    private Long count;
}
