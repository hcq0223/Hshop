package com.shopai.product.feign;

import com.shopai.common.Result;
import com.shopai.common.dto.internal.ProductIndexItem;
import com.shopai.common.dto.internal.query.IndexListQuery;
import com.shopai.common.dto.internal.query.IndexListResult;
import com.shopai.common.feign.FeignFallbackReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

@Component
public class SearchIndexClientFallback implements FallbackFactory<SearchIndexClient> {

    private static final Logger log = LoggerFactory.getLogger(SearchIndexClientFallback.class);
    @Override
    public SearchIndexClient create(Throwable cause) {
        String reason = FeignFallbackReason.describe(cause);
        if (log.isDebugEnabled()) {
            log.debug("[降级触发] <SearchIndexClientFallback-product> 进入降级, reason={}", reason, cause);
        }
        return new SearchIndexClient() {
            @Override
            public Result<IndexListResult> list(IndexListQuery query) {
                log.warn("搜索索引服务暂时不可用, 方法:list(IndexListQuery query), query={}, reason={}", query, reason);

                IndexListResult empty = new IndexListResult();
                empty.setRecords(Collections.emptyList());
                empty.setTotal(0L);
                empty.setPage(query == null || query.getPage() == null ? 1 : query.getPage());
                empty.setSize(query == null || query.getSize() == null ? 10 : query.getSize());
                empty.setPages(0L);

                return Result.ok(empty);
            }

            @Override
            public Result<Integer> upsertProducts(List<ProductIndexItem> items) {
                log.warn("搜索索引服务暂时不可用, 方法:upsertProducts(List<ProductIndexItem> items), items={}, reason={}", items, reason);
                return Result.ok(0);
            }
        };
    }
}
