package com.shopai.search.dto;

import com.shopai.search.document.ProductDocument;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 商品分面搜索结果
 * <p>
 * 微服务拆分说明：shopai-search 服务新增的「复合搜索 + 桶聚合 + post_filter 后置过滤」返回结构。
 * 典型电商分面检索：主查询（复合 bool）决定命中范围，post_filter 只做「选中分类」的结果过滤，
 * 因此 categories 里的计数始终是「当前关键词/价格条件」下的全量分类分布，不会因为选中某分类而塌缩。
 *
 * @param <T> 记录类型（当前为 ProductDocument）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FacetSearchResult<T> {

    /** 命中总数（受 post_filter 影响，即「选中分类后」的真实总数） */
    private Long total;

    /** 当前页码 */
    private Integer page;

    /** 每页条数 */
    private Integer size;

    /** 当前页商品记录 */
    private List<T> records;

    /** 分类分面桶（各分类命中数），供前端渲染筛选侧栏 */
    private List<CategoryFacet> categories;
}
