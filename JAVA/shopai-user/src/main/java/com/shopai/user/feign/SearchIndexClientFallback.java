package com.shopai.user.feign;

import com.shopai.common.Result;
import com.shopai.common.dto.internal.query.IndexListQuery;
import com.shopai.common.dto.internal.query.IndexListResult;
import com.shopai.common.feign.FeignFallbackReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;

/**
 * 搜索服务 Feign 降级实现（用户服务侧）
 * <p>
 * <b>降级语义</b>：{@code list} 承载后台用户列表查询。搜索服务不可用时降级为**空分页结果**，
 * 页面显示「暂无数据」而不是 500。
 * <p>
 * 权衡说明：空结果会让人误以为「确实没有用户」。因此用 {@code log.error} 明确记录，
 * 便于运维从日志区分「真的没数据」与「搜索服务挂了」。
 * 用户服务侧的 {@code UserListIndexService} 也会自行兜底返回空页；
 * 本降级覆盖的是「请求根本发不出去就已失败」这条路径。
 */
@Component
public class SearchIndexClientFallback implements FallbackFactory<SearchIndexClient> {

    private static final Logger log = LoggerFactory.getLogger(SearchIndexClientFallback.class);

    @Override
    public SearchIndexClient create(Throwable cause) {
        String reason = FeignFallbackReason.describe(cause);
        if (log.isDebugEnabled()) {
            log.debug("[降级触发] <SearchIndexClient-user> 进入降级, reason={}", reason, cause);
        }
        return query -> {
            log.error("搜索服务暂不可用, 用户列表查询降级为空结果（页面会显示暂无数据）, "
                    + "query={}, reason={}", query, reason);

            IndexListResult empty = new IndexListResult();
            empty.setRecords(Collections.emptyList());
            empty.setTotal(0L);
            empty.setPage(query == null || query.getPage() == null ? 1 : query.getPage());
            empty.setSize(query == null || query.getSize() == null ? 10 : query.getSize());
            empty.setPages(0L);
            return Result.ok(empty);
        };
    }
}
